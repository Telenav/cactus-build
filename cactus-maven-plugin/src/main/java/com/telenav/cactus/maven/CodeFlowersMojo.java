////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// © 2011-2022 Telenav, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
package com.telenav.cactus.maven;

import com.telenav.cactus.git.GitCheckout;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.model.Pom;
import com.telenav.cactus.maven.mojobase.ScopedCheckoutsMojo;
import com.telenav.cactus.analysis.codeflowers.CodeflowersJsonGenerator;
import com.telenav.cactus.analysis.MavenProjectsScanner;
import com.telenav.cactus.analysis.WordCount;
import com.telenav.cactus.maven.tree.ProjectTree;
import com.telenav.cactus.scope.ProjectFamily;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptySet;

/**
 * Generates CodeFlowers JSON and .wc files to the assets directory for each
 * project family in scope.
 *
 * @author Tim Boudreau
 */
@SuppressWarnings("unused")
@org.apache.maven.plugins.annotations.Mojo(
        defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
        requiresDependencyResolution = ResolutionScope.NONE,
        instantiationStrategy = InstantiationStrategy.SINGLETON,
        name = "codeflowers", threadSafe = true)
public class CodeFlowersMojo extends ScopedCheckoutsMojo
{
    /**
     * If true, generate JSON files indented for human-readability; if false,
     * omit all inter-element whitespace.
     */
    @Parameter(property = "cactus.indent", defaultValue = "false")
    private boolean indent = false;

    @Parameter(property = "cactus.tolerate.version.inconsistencies.families",
            required = false)
    private String tolerateVersionInconsistenciesIn;

    @Parameter(property = "cactus.codeflowers.skip")
    private boolean skipped;

    @Parameter(property = "cactus.families", required = false)
    private String families;

    public CodeFlowersMojo()
    {
        super(new FamilyRootRunPolicy());
    }

    private Set<ProjectFamily> tolerateVersionInconsistenciesIn()
    {
        return tolerateVersionInconsistenciesIn == null
               ? emptySet()
               : ProjectFamily.fromCommaDelimited(
                        tolerateVersionInconsistenciesIn, () -> null);
    }

    @Override
    protected void execute(BuildLog log, MavenProject project,
            GitCheckout myCheckout, ProjectTree tree,
            List<GitCheckout> checkouts) throws Exception
    {
        _execute(log, project, myCheckout, tree, applyFamilies(tree, checkouts));
    }

    private void _execute(BuildLog log, MavenProject project,
            GitCheckout myCheckout, ProjectTree tree,
            List<GitCheckout> checkouts) throws Exception
    {
        if (skipped)
        {
            log.info("Codeflowers is skipped");
            return;
        }
        Map<ProjectFamily, Set<Pom>> all = allPoms(tree, checkouts);
        for (Map.Entry<ProjectFamily, Set<Pom>> e : all.entrySet())
        {
            if (e.getValue().isEmpty())
            {
                continue;
            }
            String version = checkConsistentVersion(e.getKey(), e.getValue(),
                    tree);
            if (version == null)
            { // empty = should not happen
                log.warn("Got no versions at all in " + e.getKey());
                continue;
            }
            ProjectFamily fam = e.getKey();
            fam.assetsPath(myCheckout.submoduleRoot()
                    .map(co -> co.checkoutRoot())).ifPresentOrElse(assetsRoot ->
            {
                Path codeflowersPath = assetsRoot.resolve("docs").resolve(
                        version).resolve("codeflowers")
                        .resolve("site").resolve("data");
                log.info(
                        "Will generate codeflowers for '" + fam + "' into " + codeflowersPath);
                MavenProjectsScanner scanner = new MavenProjectsScanner(log
                        .child("scanProjects"), new WordCount(), e.getValue());
                CodeflowersJsonGenerator gen = new CodeflowersJsonGenerator(fam
                        .toString(), codeflowersPath, indent, isPretend());
                scanner.scan(gen);
            }, () ->
            {
                log.warn("Could not find an assets root for family " + fam);
            });
        }
    }

    private List<GitCheckout> applyFamilies(ProjectTree tree,
            List<GitCheckout> old) throws MojoExecutionException
    {
        // Pending - this belongs in ScopeMojo, and all mojos need updating
        // to deal with family being a potential set of families.
        // This is quick and dirty to get a release out.
        if (families != null && !families.isBlank())
        {
            Set<ProjectFamily> fams = new HashSet<>();
            for (String fam : families.split(","))
            {
                if (!fam.isBlank())
                {
                    fams.add(ProjectFamily.named(fam.trim()));
                }
            }
            Set<GitCheckout> checkouts = new LinkedHashSet<>();
            for (ProjectFamily fam : fams)
            {
                checkouts.addAll(tree.checkoutsInProjectFamily(fam));
            }
            if (checkouts.isEmpty())
            {
                fail("No checkouts in families " + fams);
            }
            log.info("Using " + " for " + families + ": " + checkouts);
            return new ArrayList<>(checkouts);
        }
        return old;
    }

    private Map<ProjectFamily, Set<Pom>> allPoms(ProjectTree tree,
            Collection<? extends GitCheckout> checkouts)
    {
        Map<ProjectFamily, Set<Pom>> result = new HashMap<>();
        for (GitCheckout co : checkouts)
        {
            for (Pom pom : tree.projectsWithin(co))
            {
                if (!pom.isPomProject())
                {
                    Set<Pom> poms = result.computeIfAbsent(ProjectFamily
                            .fromGroupId(pom.coordinates().groupId().text()),
                            f -> new HashSet<>());
                    poms.add(pom);
                }
            }
        }
        return result;
    }

    private String checkConsistentVersion(ProjectFamily fam, Set<Pom> poms,
            ProjectTree tree)
            throws Exception
    {
        Set<String> versions = new HashSet<>();
        poms.forEach(pom -> versions.add(pom.coordinates().version.text()));
        if (versions.size() > 1)
        {
            if (tolerateVersionInconsistenciesIn().contains(fam))
            {
                fam.probableFamilyVersion(poms).ifPresent(ver ->
                {
                    versions.clear();
                    versions.add(ver.text());
                });
            }
            else
            {
                throw new MojoExecutionException(
                        "Not all projects in family '" + fam + "' have the same version: " + versions);
            }
        }
        return versions.isEmpty()
               ? null
               : versions.iterator().next();
    }
}
