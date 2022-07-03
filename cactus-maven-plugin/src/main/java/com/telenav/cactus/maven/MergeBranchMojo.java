package com.telenav.cactus.maven;

import com.mastfrog.function.throwing.ThrowingTriConsumer;
import com.telenav.cactus.git.Branches;
import com.telenav.cactus.git.Branches.Branch;
import com.telenav.cactus.git.GitCheckout;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.mojobase.ScopedCheckoutsMojo;
import com.telenav.cactus.maven.tree.ProjectTree;
import com.telenav.cactus.scope.ProjectFamily;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import static com.telenav.cactus.scope.Scope.FAMILY;
import static java.util.Collections.singleton;
import static org.apache.maven.plugins.annotations.InstantiationStrategy.SINGLETON;

/**
 *
 * @author Tim Boudreau
 */
@org.apache.maven.plugins.annotations.Mojo(
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = ResolutionScope.NONE,
        instantiationStrategy = SINGLETON,
        name = "merge", threadSafe = true)
public class MergeBranchMojo extends ScopedCheckoutsMojo
{
    /**
     * Name of the branch to merge <b>from</b> - if unset, uses the current
     * branch.
     */
    @Parameter(property = "cactus.merge.from", required = false)
    String mergeFrom;

    /**
     * The branch to merge into - the default is "develop".
     */
    @Parameter(property = "cactus.merge.into", defaultValue = "develop")
    String mergeInto;

    /**
     * A second branch to merge into - e.g. merge a release branch back to
     * "develop" but first also merge it into release/current.
     */
    @Parameter(property = "cactus.also.merge.into", required = false)
    String alsoMergeInto;

    /**
     * Delete the merged branch after a successful merge.
     */
    @Parameter(property = "cactus.delete.merged.branch", defaultValue = "false")
    boolean deleteMergedBranch;

    /**
     * The set of families to merge, comma-delimited. If unset, the value of
     * cactus.family is used; if that is also unset, the family of the current
     * project is used.
     */
    @Parameter(property = "cactus.families", required = false)
    String families;

    /**
     * If true, create a git tag named from the latter portion of the target
     * branch name following any / character, if the merge succeeds.
     */
    @Parameter(property = "cactus.tag", defaultValue = "true")
    boolean tag;

    /**
     * If true, push on success.
     */
    @Parameter(property = "cactus.push", defaultValue = "false")
    boolean push;

    @Override
    protected void onValidateParameters(BuildLog log, MavenProject project)
            throws Exception
    {
        validateBranchName(mergeFrom, true);
        validateBranchName(mergeInto, false);
        validateBranchName(alsoMergeInto, true);
        switch (scope())
        {
            case FAMILY:
                break;
            default:
                if (families != null && !families.isBlank())
                {
                    fail("Cannot use cactus.families exception cactus.scope=family");
                }
        }
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
        // Pending: We could use git merge-tree, scanning for conflict
        // markers in the diff it emits, to detect if a merge will fail.
        // For what this mojo will be used for, that is likely to be rare.
        //
        // Get maps of GitCheckout:Branch for from, to and also
        withBranches(tree, checkouts, (toMergeFrom, toMergeTo, alsoMergeTo) ->
        {
            if (toMergeFrom.isEmpty() || toMergeTo.isEmpty())
            {
                log.warn("No checkouts found to merge");
                return;
            }
            log.info("Have " + toMergeFrom.size() + " checkouts to merge.");
            Set<GitCheckout> checkoutsToMerge = new HashSet<>(toMergeTo.keySet());
            checkoutsToMerge.retainAll(toMergeFrom.keySet());
            // Do the thing, one repo at a time:
            for (GitCheckout checkout : checkoutsToMerge)
            {
                Branch from = toMergeFrom.get(checkout);
                Branch to = toMergeTo.get(checkout);
                Branch also = alsoMergeTo.get(checkout);

                // If we have secondary branch to merge to, do that first,
                // so we leave the repo on the final destination branch
                if (also != null)
                {
                    log.info(
                            "First merge " + from + " into " + also + " in " + checkout
                                    .logggingName());
                    ifNotPretending(() ->
                    {
                        if (also.isRemote())
                        {
                            log.info(
                                    "Branch " + also.trackingName() + " does not exist "
                                    + "locally.  Creating it.");
                            checkout.createAndSwitchToBranch(also.name(),
                                    Optional.of(also.trackingName()));
                        }
                        else
                        {
                            checkout.switchToBranch(also.name());
                        }
                        checkout.merge(from.name());
                    });
                    if (push)
                    {
                        log.info("Push " + checkout.logggingName());
                        ifNotPretending(checkout::push);
                    }
                }
                log.info("Merge " + from + " into " + to + " in " + checkout
                        .logggingName());
                ifNotPretending(() ->
                {
                    // Get on the target branch
                    checkout.switchToBranch(to.name());
                    // Do the merge
                    checkout.merge(from.name());
                });
                if (tag)
                {
                    // Strip any leading feature/ or whatever from the branch name
                    String newTag = tagName(from);
                    log.info(
                            "Tag " + checkout.logggingName() + " with " + newTag);
                    ifNotPretending(() ->
                    {
                        // Use tag -f to force - this would be a silly thing to
                        // fail on.
                        checkout.tag(newTag, true);
                    });
                }
                if (deleteMergedBranch)
                {
                    // Nuke it.
                    log.info("Delete branch " + from + " in " + checkout
                            .logggingName());
                    ifNotPretending(() ->
                    {
                        checkout.deleteBranch(from.name(), to.name(), false);
                    });
                }
                if (push)
                {
                    // The target branch may not exists, which requires a
                    // different push call
                    boolean remoteBranchExists = tree.branches(checkout).find(to
                            .name(), false).isPresent();
                    if (remoteBranchExists)
                    {
                        checkout.push();
                    }
                    else
                    {
                        checkout.pushCreatingBranch();
                    }
                }
            }
        });
    }

    private void withBranches(ProjectTree tree, List<GitCheckout> checkouts,
            ThrowingTriConsumer<Map<GitCheckout, Branch>, Map<GitCheckout, Branch>, Map<GitCheckout, Branch>> c)
            throws Exception
    {
        if (isIncludeRoot() && !checkouts.contains(tree.root()))
        {
            checkouts = new ArrayList<>(checkouts);
            checkouts.add(tree.root());
        }
        try
        {
            // Collect all our branch info
            Map<GitCheckout, Branch> toMergeFrom = new LinkedHashMap<>();
            Map<GitCheckout, Branch> toMergeTo = new LinkedHashMap<>();
            Map<GitCheckout, Branch> additionalDestinations = new LinkedHashMap<>();
            for (GitCheckout checkout : checkouts)
            {
                Branches branches = tree.branches(checkout);

                Optional<Branch> branchToMergeFrom;
                if (mergeFrom != null)
                {
                    // We have an explicit branch
                    branchToMergeFrom = branches.find(mergeFrom, true);
                }
                else
                {
                    // Just use whatever the local branch is
                    branchToMergeFrom = branches.currentBranch();
                }
                // If something is missing, that just means it was not touched
                // in whatever performed the changes
                if (branchToMergeFrom.isPresent())
                {
                    toMergeFrom.put(checkout, branchToMergeFrom.get());
                }

                branches.find(mergeInto, true).ifPresent(
                        branchToMergeTo
                        -> toMergeTo.put(checkout,
                                branchToMergeTo));
                if (alsoMergeInto != null)
                {
                    branches.find(alsoMergeInto, true)
                            .or(() -> branches.find(alsoMergeInto, false))
                            .ifPresent(also ->
                            {
                                additionalDestinations.put(checkout, also);
                            });
                }
            }

            // Winnow out those we don't have a branch for on both sides:
            Set<GitCheckout> common = new HashSet<>(toMergeTo.keySet());
            common.retainAll(toMergeFrom.keySet());
            Set<GitCheckout> toRemove = new HashSet<>(toMergeTo.keySet());
            toRemove.addAll(toMergeFrom.keySet());
            toRemove.removeAll(common);
            for (GitCheckout rem : toRemove)
            {
                toMergeFrom.remove(rem);
                toMergeTo.remove(rem);
            }
            toRemove.clear();
            // We also may have picked up repos where we would merge a branch
            // into itself, if the target branch name was unspecified.  Prune
            // them too.
            toMergeFrom.forEach((repo, branch) ->
            {
                if (branch.equals(toMergeTo.get(repo)))
                {
                    toRemove.add(repo);
                }
            });
            for (GitCheckout rem : toRemove)
            {
                toMergeFrom.remove(rem);
                toMergeTo.remove(rem);
            }

            c.accept(toMergeFrom, toMergeTo, additionalDestinations);
        }
        finally
        {
            tree.invalidateCache();
        }
    }

    private List<GitCheckout> applyFamilies(ProjectTree tree,
            List<GitCheckout> orig)
    {
        if (families == null || scope() != FAMILY)
        {
            return orig;
        }
        Set<ProjectFamily> fams = families();
        if (fams.equals(singleton(projectFamily())))
        {
            return orig;
        }
        Set<GitCheckout> result = new LinkedHashSet<>();
        for (ProjectFamily fam : fams)
        {
            result.addAll(tree.checkoutsInProjectFamily(fam));
        }
        return new ArrayList<>(result);
    }

    private String tagName(Branch fromBranch)
    {
        String nm = fromBranch.name();
        int ix = nm.lastIndexOf('/');
        if (ix > 0 && ix < nm.length() - 1)
        {
            return nm.substring(ix + 1);
        }
        return nm;
    }

    private Set<ProjectFamily> families()
    {
        return ProjectFamily.fromCommaDelimited(families, super::projectFamily);
    }
}