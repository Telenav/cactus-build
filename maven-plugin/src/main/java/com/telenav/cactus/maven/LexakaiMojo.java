package com.telenav.cactus.maven;

import com.mastfrog.function.throwing.ThrowingRunnable;
import com.telenav.cactus.maven.git.GitCheckout;
import static com.telenav.cactus.maven.git.GitCheckout.reverseDepthSort;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.tree.ProjectTree;
import com.telenav.cactus.maven.util.PathUtils;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.plugin.MojoFailureException;
import static org.apache.maven.plugins.annotations.InstantiationStrategy.SINGLETON;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;

/**
 * Runs lexakai to generate documentation and diagrams for a project into some
 * folder. This mojo is intended to be used only on the root of a family of
 * projects.
 * <p>
 * The destination folder for documentation is computed as follows:
 * </p>
 * <ol>
 * <li>If the <code>output-folder</code> parameter is passed with
 * <code>-Doutput-folder=path/to/output</code> or set in the
 * <code>&lt;configuration&rt;</code>, section of the invoking
 * <code>pom.xml</code> or its parents, that path will be used unmodified.</li>
 * <li>If an assets-home environment variable is set, used that. The environment
 * variable is computed as follows:
 * <ol>
 * <li>Take the suffix of the project's group-id</li>
 * <li>If it contains a hyphen, trim it to the text preceding the first
 * hyphen</li>
 * <li>Convert the string to upper case</li>
 * <li>Append <code>_ASSETS_HOME</code> to that</li>
 * </ol>
 * So, if you have a group id <code>com.telenav.kivakit</code>, the environment
 * variable is <code>KIVAKIT_ASSETS_HOME</code>; if you have a group id
 * <code>edu.stuff.foo-bar-baz</code> the environment variable is
 * <code>FOO_ASSETS_HOME</code>.
 * </li>
 * <li>If the environment variable is unset, then
 * <ul>
 * <li>Use steps 1 and 2 above to compute the project family name, and then</li>
 * <li>Find the git submodule root checkout above the project's base dir</li>
 * <li>Look for a folder named <code>$PROJECT_FAMILY-assets</code> and use it if
 * it exists</li>
 * </ul>
 * <li>If all of the above fail, output to <code>target/lexakai</code></li>
 * </ol>
 *
 * @author Tim Boudreau
 */
@org.apache.maven.plugins.annotations.Mojo(
        defaultPhase = LifecyclePhase.SITE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        instantiationStrategy = SINGLETON,
        name = "lexakai", threadSafe = true)
public class LexakaiMojo extends BaseMojo
{

    /**
     * If true, instruct lexakai to overwrite resources.
     */
    @Parameter(property = "overwrite-resources", defaultValue = "true", name = "overwrite-resources")
    private boolean overwriteResources;

    /**
     * If true, instruct lexakai to update readme files.
     */
    @Parameter(property = "update-readme", defaultValue = "true", name = "update-readme")
    private boolean updateReadme;

    /**
     * If true, log the commands being passed to lexakai.
     */
    @Parameter(property = "verbose", defaultValue = "true")
    private boolean verbose;

    /**
     * If true, don't really run lexakai.
     */
    @Parameter(property = "lexakai.skip", defaultValue = "false")
    private boolean skip;

    /**
     * The destination folder for generated documentation - if unset, it is
     * computed as described above.
     */
    @Parameter(property = "output-folder", name = "output-folder")
    private String outputFolder;

    /**
     * The destination folder for generated documentation - if unset, it is
     * computed as described above.
     */
    @Parameter(property = "commit-changes", name = "commit-changes", defaultValue = "false")
    private boolean commitChanges;

    @Override
    protected boolean isOncePerSession()
    {
        return false;
    }

    @Override
    protected void performTasks(BuildLog log, MavenProject project) throws Exception
    {
        Path outputDir = output(project);
        List<String> args = Arrays.asList(
                "-update-readme=" + updateReadme,
                "-overwrite-resources=" + overwriteResources,
                "-output-folder=" + outputDir,
                project.getBasedir().toString()
        );
        if (verbose)
        {
            log.info("Lexakai args:");
            log.info("lexakai " + args);
        }
        if (!skip)
        {
            ThrowingRunnable runner = runLexakai(args);
            if (commitChanges)
            {
                // Returns the set of repositories which were _not_ modified
                // *before* we ran lexakai, but are now
                Set<GitCheckout> modified = collectedChangedRepos(project, runner);
                if (!modified.isEmpty())
                {
                    // Commit each repo in deepest-child down order
                    String msg = commitMessage(project, modified);
                    for (GitCheckout ch : reverseDepthSort(modified))
                    {
                        if (!ch.addAll())
                        {
                            log.error("Add all failed in " + ch);
                            continue;
                        }
                        if (!ch.commit(msg))
                        {
                            log.error("Commit failed in " + ch);
                        }
                    }
                    // Committing child repos may have generated changes in the
                    // set of commits the submodule root points to, so make sure
                    // we generate a final commit here so it points to our updates
                    GitCheckout.repository(project.getBasedir())
                            .flatMap(prjCheckout -> prjCheckout.submoduleRoot().toOptional())
                            .ifPresent(root ->
                            {
                                if (root.isDirty())
                                {
                                    if (!root.addAll())
                                    {
                                        log.error("Add all failed in " + root);
                                    }
                                    if (!root.commit(msg))
                                    {
                                        log.error("Commit failed in " + root);
                                    }
                                }
                            });
                }
            } else
            {
                runner.run();
            }
        }
    }

    private ThrowingRunnable runLexakai(List<String> args) throws Exception
    {
        return new LexakaiRunner(lexakaiJar(), args);
    }

    private Set<GitCheckout> collectedChangedRepos(MavenProject prj, ThrowingRunnable toRun)
    {
        return ProjectTree.from(prj).map(tree ->
        {
            Set<GitCheckout> needingCommitBefore = collectModifiedCheckouts(tree);
            toRun.run();
            Set<GitCheckout> needingCommitAfter = collectModifiedCheckouts(tree);
            needingCommitAfter.removeAll(needingCommitBefore);
            return needingCommitAfter;
        }).orElseGet(() ->
        {
            toRun.run();
            return Collections.emptySet();
        });
    }

    private final Set<GitCheckout> collectModifiedCheckouts(ProjectTree tree)
    {
        tree.invalidateCache();
        Set<GitCheckout> needingCommit = new HashSet<>();
        if (tree.isDirty(tree.root()))
        {
            needingCommit.add(tree.root());
        }
        for (GitCheckout gc : tree.nonMavenCheckouts())
        {
            if (gc.isDirty())
            {
                needingCommit.add(gc);
            }
        }
        for (GitCheckout gc : tree.allCheckouts())
        {
            if (gc.isDirty())
            {
                needingCommit.add(gc);
            }
        }
        return needingCommit;
    }

    private String commitMessage(MavenProject prj, Set<GitCheckout> checkouts)
    {
        StringBuilder sb = new StringBuilder("Generated commit ")
                .append(prj.getGroupId())
                .append(":")
                .append(prj.getArtifactId())
                .append(":")
                .append(prj.getVersion())
                .append("\n\n");

        String user = System.getProperty("user.name");
        Path home = PathUtils.home();
        String ver = System.getProperty("java.version");
        String host = System.getenv("HOST");
        sb.append("User:\t").append(user);
        sb.append("\nHome:\t").append(home);
        sb.append("\nHost:\t").append(host);
        sb.append("\nWhen:\t").append(Instant.now());
        sb.append("\n\n").append("Modified checkouts:\n");
        for (GitCheckout ch : checkouts)
        {
            sb.append("\n  * ").append(ch.name()).append(" (").append(ch.checkoutRoot()).append(")");
        }
        return sb.append("\n").toString();
    }

    Path output(MavenProject project)
    {
        // If the output folder was explicitly specified, use it.
        if (outputFolder != null)
        {
            return Paths.get(outputFolder);
        }
        // If the environment variable is set, use it
        String envValue = System.getenv(environmentVariableName(project));
        if (envValue != null)
        {
            return Paths.get(envValue);
        }
        // Find the root of the checkout, and if there is a
        // groupIdAfterLastDot-assets folder, use that; if not,
        // invent a target/lexakai dir in target/ for output
        return GitCheckout.repository(project.getBasedir()).flatMap(repo ->
        {
            return repo.submoduleRoot().flatMap(subroot
                    -> PathUtils.ifDirectory(
                            subroot.checkoutRoot().resolve(prefix(project) + "-assets"))).toOptional();
        }).orElseGet(() -> project.getBasedir().toPath().resolve("target").resolve("lexakai"));
    }

    private String prefix(MavenProject project)
    {
        String gid = project.getGroupId();
        int ix = gid.lastIndexOf('.');
        if (ix >= 0 && ix < gid.length() - 1)
        {
            return gid.substring(ix + 1);
        }
        return gid;
    }

    private String environmentVariableName(MavenProject project)
    {
        String tail = prefix(project).toUpperCase();
        int hyphen = tail.indexOf('-');
        if (hyphen > 0)
        {
            tail = tail.substring(0, hyphen);
        }
        return tail + "_ASSETS_HOME";
    }

    private Path lexakaiJar() throws MojoFailureException
    {
        // PENDING: We should pass in a target version of lexakai, not hard-code it
        Artifact af = new DefaultArtifact("com.telenav.lexakai", "Lexakai", "jar", "1.0.7");
        LocalArtifactRequest locArtifact = new LocalArtifactRequest();
        locArtifact.setArtifact(af);
        RemoteRepository remoteRepo = new RemoteRepository.Builder("central",
                "x", "https://repo1.maven.org/maven2")
                .build();
        locArtifact.setRepositories(Collections.singletonList(remoteRepo));
        RepositorySystemSession sess = session().getRepositorySession();
        LocalArtifactResult res = sess.getLocalRepositoryManager().find(sess, locArtifact);
        log.warn("Download result for " + af + ": " + res);
        if (res != null && res.getFile() != null)
        {
            log.warn("Have local lexakai jar " + res.getFile());
            return res.getFile().toPath();
        }
        throw new MojoFailureException("Could not download " + af + " from " + remoteRepo.getUrl());
    }

    class LexakaiRunner implements ThrowingRunnable
    {

        private final Path jarFile;
        private final List<String> args;
        private final BuildLog runLog = BuildLog.get().child("LexakaiRunner");

        LexakaiRunner(Path jarFile, List<String> args)
        {
            this.jarFile = jarFile;
            this.args = args;
        }

        @Override
        public void run() throws Exception
        {
            ClassLoader ldr = Thread.currentThread().getContextClassLoader();

            try
            {
                URL[] url = new URL[]
                {
                    new URL("jar:" + jarFile.toUri().toURL() + "!/")
                };
                runLog.warn("Invoke lexakai reflectivly from " + url[0]);
                try ( URLClassLoader jarLoader = new URLClassLoader("lexakai", url, ldr))
                {
                    Thread.currentThread().setContextClassLoader(jarLoader);
                    Class<?> what = jarLoader.loadClass("com.telenav.lexakai.Lexakai");
                    runLog.warn("Have class " + what);
                    Method mth = what.getMethod("main", String[].class);
                    System.out.println("Have method " + mth);
                    mth.invoke(null, (Object) args.toArray(String[]::new));
                    System.out.println("Lexakai done.");
                }
            } finally
            {
                Thread.currentThread().setContextClassLoader(ldr);
            }
        }

    }
}
