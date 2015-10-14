/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2015 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.registry.maven;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.connection.DBPDriverContext;
import org.jkiss.dbeaver.runtime.RuntimeUtils;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maven artifact version descriptor (POM).
 */
public class MavenArtifactVersion {
    static final Log log = Log.getLog(MavenArtifactVersion.class);

    public static final String PROP_PROJECT_VERSION = "project.version";
    public static final String PROP_PROJECT_GROUP_ID = "project.groupId";
    public static final String PROP_PROJECT_ARTIFACT_ID = "project.artifactId";
    private static final String DEFAULT_PROFILE_ID = "#root";

    private MavenArtifact artifact;
    private String name;
    private String version;
    private String description;
    private String url;
    private MavenArtifactVersion parent;
    private List<MavenArtifactVersion> imports;
    private final List<MavenArtifactLicense> licenses = new ArrayList<>();
    private final List<MavenProfile> profiles = new ArrayList<>();

    private GeneralUtils.IVariableResolver propertyResolver = new GeneralUtils.IVariableResolver() {
        @Override
        public String get(String name) {
            switch (name) {
                case PROP_PROJECT_VERSION:
                    return version;
                case PROP_PROJECT_GROUP_ID:
                    return artifact.getGroupId();
                case PROP_PROJECT_ARTIFACT_ID:
                    return artifact.getArtifactId();
            }
            for (MavenArtifactVersion v = MavenArtifactVersion.this; v != null; v = v.parent) {
                for (MavenProfile profile : v.profiles) {
                    if (!profile.isActive()) {
                        continue;
                    }
                    String value = profile.properties.get(name);
                    if (value != null) {
                        return value;
                    }
                }
            }
            return null;
        }
    };

    MavenArtifactVersion(@NotNull DBPDriverContext context, @NotNull MavenArtifact artifact, @NotNull String version, boolean readRemote) throws IOException {
        this.artifact = artifact;
        this.version = version;
        loadPOM(context, readRemote);
    }

    public MavenArtifact getArtifact() {
        return artifact;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public MavenArtifactVersion getParent() {
        return parent;
    }

    public List<MavenArtifactLicense> getLicenses() {
        return licenses;
    }

    public List<MavenArtifactDependency> getDependencies(DBPDriverContext context) {
        List<MavenArtifactDependency> dependencies = new ArrayList<>();
        for (MavenProfile profile : profiles) {
            if (profile.isActive() && !CommonUtils.isEmpty(profile.dependencies)) {
                dependencies.addAll(profile.dependencies);
            }
        }
        if (parent != null) {
            List<MavenArtifactDependency> parentDependencies = parent.getDependencies(context);
            if (!CommonUtils.isEmpty(parentDependencies)) {
                dependencies.addAll(parentDependencies);
            }
        }
        return dependencies;
    }

    public File getCacheFile() {
        if (artifact.getRepository().isLocal()) {
            String externalURL = getExternalURL(MavenArtifact.FILE_JAR);
            try {
                return new File(new URL(externalURL).toURI());
            } catch (Exception e) {
                log.warn("Bad repository URL", e);
                return new File(externalURL);
            }
        }
        return new File(artifact.getRepository().getLocalCacheDir(), artifact.getGroupId() + "/" + artifact.getVersionFileName(version, MavenArtifact.FILE_JAR));
    }

    public String getExternalURL(String fileType) {
        return artifact.getFileURL(version, fileType);
    }

    public String getPath() {
        return artifact.toString() + ":" + version;
    }

    @Override
    public String toString() {
        return getPath();
    }

    private File getLocalPOM() {
        if (artifact.getRepository().isLocal()) {
            try {
                return new File(new URI(getRemotePOMLocation()));
            } catch (URISyntaxException e) {
                log.warn(e);
            }
        }
        return new File(
            artifact.getRepository().getLocalCacheDir(),
            artifact.getGroupId() + "/" + artifact.getVersionFileName(version, MavenArtifact.FILE_POM));
    }

    private String getRemotePOMLocation() {
        return artifact.getFileURL(version, MavenArtifact.FILE_POM);
    }

    private void loadPOM(DBPDriverContext context, boolean readRemote) throws IOException {
        File localPOM = getLocalPOM();
        if (!localPOM.exists()) {
            if (readRemote) {
                cachePOM(localPOM);
            } else {
                log.warn("Local POM missing for " + this);
                return;
            }
        }

        context.getMonitor().subTask("Load POM " + this);

        Document pomDocument;
        try (InputStream mdStream = new FileInputStream(localPOM)) {
            pomDocument = XMLUtils.parseDocument(mdStream);
        } catch (XMLException e) {
            throw new IOException("Error parsing POM", e);
        }
        Element root = pomDocument.getDocumentElement();
        name = XMLUtils.getChildElementBody(root, "name");
        url = XMLUtils.getChildElementBody(root, "url");
        version = XMLUtils.getChildElementBody(root, "version");
        description = XMLUtils.getChildElementBody(root, "description");
        {
            // Parent
            Element parentElement = XMLUtils.getChildElement(root, "parent");
            if (parentElement != null) {
                String parentGroupId = XMLUtils.getChildElementBody(parentElement, "groupId");
                String parentArtifactId = XMLUtils.getChildElementBody(parentElement, "artifactId");
                String parentVersion = XMLUtils.getChildElementBody(parentElement, "version");
                if (parentGroupId == null || parentArtifactId == null || parentVersion == null) {
                    log.error("Broken parent reference: " + parentGroupId + ":" + parentArtifactId + ":" + parentVersion);
                } else {
                    MavenArtifactReference parentReference = new MavenArtifactReference(
                        parentGroupId,
                        parentArtifactId,
                        parentVersion
                    );
                    if (this.version == null) {
                        this.version = parentReference.getVersion();
                    }
                    parent = MavenRegistry.getInstance().findArtifact(context, parentReference);
                    if (parent == null) {
                        log.error("Artifact [" + this + "] parent [" + parentReference + "] not found");
                    }
                }
            }
        }

        {
            // Licenses
            Element licensesElement = XMLUtils.getChildElement(root, "licenses");
            if (licensesElement != null) {
                for (Element prop : XMLUtils.getChildElementList(licensesElement, "license")) {
                    licenses.add(new MavenArtifactLicense(
                        XMLUtils.getChildElementBody(prop, "name"),
                        XMLUtils.getChildElementBody(prop, "url")
                    ));
                }
            }
        }

        // Default profile
        MavenProfile defaultProfile = new MavenProfile(DEFAULT_PROFILE_ID);
        defaultProfile.active = true;
        profiles.add(defaultProfile);
        parseProfile(context, defaultProfile, root);

        {
            // Profiles
            Element licensesElement = XMLUtils.getChildElement(root, "profiles");
            if (licensesElement != null) {
                for (Element profElement : XMLUtils.getChildElementList(licensesElement, "profile")) {
                    MavenProfile profile = new MavenProfile(XMLUtils.getChildElementBody(profElement, "id"));
                    profiles.add(profile);
                    parseProfile(context, profile, profElement);
                }
            }
        }

        context.getMonitor().worked(1);
    }

    private void parseProfile(DBPDriverContext context, MavenProfile profile, Element element) {
        {
            // Activation
            Element activationElement = XMLUtils.getChildElement(element, "activation");
            if (activationElement != null) {
                String activeByDefault = XMLUtils.getChildElementBody(activationElement, "activeByDefault");
                if (!CommonUtils.isEmpty(activeByDefault)) {
                    profile.active = CommonUtils.getBoolean(activeByDefault);
                }
                String jdk = XMLUtils.getChildElementBody(activationElement, "jdk");
                if (!CommonUtils.isEmpty(jdk)) {
                    profile.active = MavenArtifact.versionMatches(System.getProperty("java.version"), jdk);
                }
                Element osElement = XMLUtils.getChildElement(activationElement, "os");
                if (osElement != null) {

                }
                Element propElement = XMLUtils.getChildElement(activationElement, "property");
                if (propElement != null) {
                    String propName = XMLUtils.getChildElementBody(propElement, "name");
                    String propValue = XMLUtils.getChildElementBody(propElement, "value");
                    // TODO: implement real properties checks. Now enable all profiles with !prop
                    if (propName != null && propName.startsWith("!")) {
                        profile.active = true;
                    }
                }
            }
        }
        if (!profile.active) {
            // Do not parse dependencies of non-active profiles (most likely they will fail).
            return;
        }
        {
            // Properties
            Element propsElement = XMLUtils.getChildElement(element, "properties");
            if (propsElement != null) {
                for (Element prop : XMLUtils.getChildElementList(propsElement)) {
                    profile.properties.put(prop.getTagName(), XMLUtils.getElementBody(prop));
                }
            }
        }
        {
            // Repositories
            Element repsElement = XMLUtils.getChildElement(element, "repositories");
            if (repsElement != null) {
                for (Element repElement : XMLUtils.getChildElementList(repsElement, "repository")) {
                    MavenRepository repository = new MavenRepository(
                        XMLUtils.getChildElementBody(repElement, "id"),
                        XMLUtils.getChildElementBody(repElement, "name"),
                        XMLUtils.getChildElementBody(repElement, "url"),
                        false);
                    String layout = XMLUtils.getChildElementBody(repElement, "layout");
                    if ("legacy".equals(layout)) {
                        log.debug("Skip legacy repository [" + repository + "]");
                        continue;
                    }
                    Element releasesElement = XMLUtils.getChildElement(repElement, "releases");
                    if (releasesElement == null) {
                        continue;
                    }
                    boolean enabled = CommonUtils.toBoolean(XMLUtils.getChildElementBody(releasesElement, "enabled"));
                    if (enabled) {
                        MavenContextInfo mci = context.getInfo(MavenContextInfo.class);
                        //mci.addRepositories();
                    }
                }
            }
        }
        {
            // Dependencies
            Element dmElement = XMLUtils.getChildElement(element, "dependencyManagement");
            if (dmElement != null) {
                profile.dependencyManagement = parseDependencies(context, dmElement, true);
            }
            profile.dependencies = parseDependencies(context, element, false);
        }
    }

    private void cachePOM(File localPOM) throws IOException {
        if (artifact.getRepository().isLocal()) {
            return;
        }
        String pomURL = getRemotePOMLocation();
        try (InputStream is = RuntimeUtils.openConnectionStream(pomURL)) {
            File folder = localPOM.getParentFile();
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Can't create cache folder '" + folder.getAbsolutePath() + "'");
            }

            try (OutputStream os = new FileOutputStream(localPOM)) {
                IOUtils.fastCopy(is, os);
            }
        }
    }

    private List<MavenArtifactDependency> parseDependencies(DBPDriverContext context, Element element, boolean depManagement) {
        List<MavenArtifactDependency> result = new ArrayList<>();
        Element dependenciesElement = XMLUtils.getChildElement(element, "dependencies");
        if (dependenciesElement != null) {
            for (Element dep : XMLUtils.getChildElementList(dependenciesElement, "dependency")) {
                String groupId = evaluateString(XMLUtils.getChildElementBody(dep, "groupId"));
                String artifactId = evaluateString(XMLUtils.getChildElementBody(dep, "artifactId"));
                if (groupId == null || artifactId == null) {
                    log.warn("Broken dependency reference: " + groupId + ":" + artifactId);
                    continue;
                }
                MavenArtifactDependency.Scope scope = MavenArtifactDependency.Scope.COMPILE;
                String scopeName = XMLUtils.getChildElementBody(dep, "scope");
                if (!CommonUtils.isEmpty(scopeName)) {
                    scope = MavenArtifactDependency.Scope.valueOf(scopeName.toUpperCase(Locale.ENGLISH));
                }
                boolean optional = CommonUtils.getBoolean(XMLUtils.getChildElementBody(dep, "optional"), false);

                String version = evaluateString(XMLUtils.getChildElementBody(dep, "version"));

                if (depManagement && scope == MavenArtifactDependency.Scope.IMPORT) {
                    // Import another pom
                    if (version == null) {
                        log.error("Missing imported artifact [" + groupId + ":" + artifactId + "] version. Skip.");
                        continue;
                    }
                    MavenArtifactReference importReference = new MavenArtifactReference(
                        groupId,
                        artifactId,
                        version);
                    MavenArtifactVersion importedVersion = MavenRegistry.getInstance().findArtifact(context, importReference);
                    if (importedVersion == null) {
                        log.error("Imported artifact [" + importReference + "] not found. Skip.");
                    }
                    if (imports == null) {
                        imports = new ArrayList<>();
                    }
                    imports.add(importedVersion);
                } else if (depManagement || (!optional && includesScope(scope))) {
                    // TODO: maybe we should include optional or PROVIDED

                    if (version == null) {
                        version = findDependencyVersion(context, groupId, artifactId);
                    }
                    if (version == null) {
                        log.error("Can't resolve artifact [" + groupId + ":" + artifactId + "] version. Skip.");
                        continue;
                    }

                    MavenArtifactDependency dependency = new MavenArtifactDependency(
                        evaluateString(groupId),
                        evaluateString(artifactId),
                        evaluateString(version),
                        scope,
                        optional
                    );
                    result.add(dependency);

                    if (!depManagement) {
                        // Exclusions
                        Element exclusionsElement = XMLUtils.getChildElement(dep, "exclusions");
                        if (exclusionsElement != null) {
                            for (Element exclusion : XMLUtils.getChildElementList(exclusionsElement, "exclusion")) {
                                dependency.addExclusion(
                                    new MavenArtifactReference(
                                        CommonUtils.notEmpty(XMLUtils.getChildElementBody(exclusion, "groupId")),
                                        CommonUtils.notEmpty(XMLUtils.getChildElementBody(exclusion, "artifactId")),
                                        ""));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean includesScope(MavenArtifactDependency.Scope scope) {
        return
            scope == MavenArtifactDependency.Scope.COMPILE ||
            scope == MavenArtifactDependency.Scope.RUNTIME/* ||
            scope == MavenArtifactDependency.Scope.PROVIDED*/;
    }

    private String findDependencyVersion(DBPDriverContext context, String groupId, String artifactId) {
        for (MavenProfile profile : profiles) {
            if (profile.isActive() && profile.dependencyManagement != null) {
                for (MavenArtifactDependency dmArtifact : profile.dependencyManagement) {
                    if (dmArtifact.getGroupId().equals(groupId) &&
                        dmArtifact.getArtifactId().equals(artifactId)) {
                        return dmArtifact.getVersion();
                    }
                }
            }
        }
        // Check in imported BOMs
        if (imports != null) {
            for (MavenArtifactVersion i : imports) {
                String dependencyVersion = i.findDependencyVersion(context, groupId, artifactId);
                if (dependencyVersion != null) {
                    return dependencyVersion;
                }
            }
        }
        return parent == null ? null : parent.findDependencyVersion(context, groupId, artifactId);
    }

    private String evaluateString(String value) {
        if (value == null) {
            return null;
        }
        return GeneralUtils.replaceVariables(value, propertyResolver);
    }

}