/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.aem.guides.wknd.core.listeners;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.Replicator;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Automatically normalises HtmlPageItemsConfig after every FE pipeline run.
 *
 * AEM's SiteThemeUpdateFrontendCodeDeploymentEventListener recreates the
 * HtmlPageItemsConfig child structure using name/value child nodes
 * (e.g. &lt;href name="href" value="/theme/site.css"/&gt;).
 * Core Components 2.x HtmlPageItemImpl resource-fallback path reads flat JCR
 * string properties via ValueMap — it cannot see child-node-style attributes,
 * so pages render bare &lt;link&gt; and &lt;script&gt; tags with no CDN URLs.
 *
 * This listener fires on every change to HtmlPageItemsConfig, detects the
 * name/value format, converts each attributes/ child to flat JCR string
 * properties, and replicates the corrected node to Publish — all in-process.
 */
@Component(
    service = ResourceChangeListener.class,
    immediate = true,
    property = {
        ResourceChangeListener.PATHS + "=/conf/wknd/sling:configs/"
            + "com.adobe.cq.wcm.core.components.config.HtmlPageItemsConfig",
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED"
    }
)
public class HtmlPageItemsConfigNormalizer implements ResourceChangeListener {

    private static final Logger log = LoggerFactory.getLogger(HtmlPageItemsConfigNormalizer.class);

    static final String CONFIG_PATH =
        "/conf/wknd/sling:configs/com.adobe.cq.wcm.core.components.config.HtmlPageItemsConfig";

    static final String SUBSERVICE = "htmlPageItemsConfigFixer";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private Replicator replicator;

    @Reference
    private SlingSettingsService settingsService;

    @Override
    public void onChange(List<ResourceChange> changes) {
        // Only run on Author — Publish has no replication outbox and the
        // content is received read-only via Sling Content Distribution.
        if (!settingsService.getRunModes().contains("author")) {
            return;
        }

        // Check whether the root config node or any of its descendants changed.
        boolean relevant = changes.stream()
            .anyMatch(c -> c.getPath().equals(CONFIG_PATH)
                || c.getPath().startsWith(CONFIG_PATH + "/"));
        if (!relevant) {
            return;
        }

        Map<String, Object> authInfo =
            Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {
            Resource configRoot = resolver.getResource(CONFIG_PATH);
            if (configRoot == null) {
                log.warn("HtmlPageItemsConfig not found at {}", CONFIG_PATH);
                return;
            }

            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                return;
            }

            boolean modified = false;

            for (Resource item : configRoot.getChildren()) {
                String name = item.getName();
                // Skip system nodes
                if (name.startsWith("jcr:") || name.startsWith("sling:")
                        || name.startsWith("rep:")) {
                    continue;
                }

                Resource attributesRes = item.getChild("attributes");
                if (attributesRes == null) {
                    continue;
                }

                Node attrNode = attributesRes.adaptTo(Node.class);
                if (attrNode == null) {
                    continue;
                }

                if (isNameValueFormat(attrNode)) {
                    log.info("HtmlPageItemsConfigNormalizer: converting name/value "
                        + "attribute nodes to flat JCR props at {}", attributesRes.getPath());
                    convertToFlatProps(attrNode);
                    modified = true;
                }
            }

            if (modified) {
                session.save();
                log.info("HtmlPageItemsConfigNormalizer: saved flat props, "
                    + "triggering replication to Publish for {}", CONFIG_PATH);
                replicator.replicate(session, ReplicationActionType.ACTIVATE, CONFIG_PATH);
            }

        } catch (LoginException e) {
            log.error("HtmlPageItemsConfigNormalizer: cannot obtain service resolver "
                + "(check service user mapping for '{}')", SUBSERVICE, e);
        } catch (RepositoryException e) {
            log.error("HtmlPageItemsConfigNormalizer: JCR error during conversion", e);
        } catch (ReplicationException e) {
            log.error("HtmlPageItemsConfigNormalizer: replication failed after conversion", e);
        }
    }

    /**
     * Returns true when at least one child of the attributes node has both
     * a "name" property and a "value" property — the old CA Config format.
     */
    private boolean isNameValueFormat(Node attrNode) throws RepositoryException {
        NodeIterator children = attrNode.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            if (child.hasProperty("name") && child.hasProperty("value")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads all name/value child nodes, removes them, then writes each pair
     * as a flat JCR string property on the attributes node itself.
     */
    private void convertToFlatProps(Node attrNode) throws RepositoryException {
        // Collect before removing
        List<String[]> pairs = new ArrayList<>();
        NodeIterator children = attrNode.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            if (child.hasProperty("name") && child.hasProperty("value")) {
                pairs.add(new String[]{
                    child.getProperty("name").getString(),
                    child.getProperty("value").getString()
                });
            }
        }

        // Remove all child nodes
        children = attrNode.getNodes();
        while (children.hasNext()) {
            children.nextNode().remove();
        }

        // Write as flat string properties
        for (String[] pair : pairs) {
            attrNode.setProperty(pair[0], pair[1]);
        }
    }
}
