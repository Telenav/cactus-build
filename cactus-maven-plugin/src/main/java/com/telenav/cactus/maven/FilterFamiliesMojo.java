package com.telenav.cactus.maven;

import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.mojobase.BaseMojo;
import com.telenav.cactus.scope.ProjectFamily;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toCollection;

/**
 * Certain targets, when run from a root pom, will result in building or
 * generating code for every project in the tree, regardless of whether they are
 * in the set of families relevant to the build. This mojo simply looks for the
 * project family list set in cactus.family or cactus.families, and takes a list
 * of "skip" properties that it will set to true for those properties <b>not</b>
 * a member of that family - so we avoid, say, generating lexakai documentation
 * during a release for a project we are not releasing.
 *
 * @author Tim Boudreau
 */
@SuppressWarnings("unused")
@org.apache.maven.plugins.annotations.Mojo(
        defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.NONE,
        instantiationStrategy = InstantiationStrategy.PER_LOOKUP,
        name = "filter-families", threadSafe = true)
public class FilterFamiliesMojo extends BaseMojo
{

    @Parameter(property = "cactus.family", required = false)
    private String family;

    @Parameter(property = "cactus.families", required = false)
    private String families;

    @Parameter(property = "cactus.properties", required = false)
    private String properties;

    @Override
    protected void performTasks(BuildLog log, MavenProject project) throws Exception
    {
        if (properties == null || properties.isBlank())
        {
            return;
        }
        Set<ProjectFamily> families = families();
        if (families.isEmpty())
        {
            return;
        }
        List<MavenProject> projectsToSetPropertiesFor = new HashSet<>(session()
                .getAllProjects())
                .stream().filter(x ->
                {
                    ProjectFamily fam = ProjectFamily.fromGroupId(project
                            .getGroupId());
                    return !families.contains(fam);
                }).collect(toCollection(ArrayList::new));
        for (String prop : properties.split(","))
        {
            prop = prop.trim();
            if (!prop.isEmpty())
            {
                for (MavenProject prj : projectsToSetPropertiesFor)
                {
                    if (isVerbose())
                    {
                        log.info("Inject " + prop + "=true into " + prj
                                .getArtifactId());
                    }
                    prj.getProperties().setProperty(prop, "true");
                }
            }
        }
    }

    private Set<ProjectFamily> families()
    {
        if (family != null || families != null)
        {
            if (families != null)
            {
                return ProjectFamily.fromCommaDelimited(families,
                        () -> family == null
                              ? null
                              : ProjectFamily.named(family));
            }
            if (family != null && !family.isBlank())
            {
                return singleton(ProjectFamily.named(family.trim()));
            }
        }
        return emptySet();
    }
}
