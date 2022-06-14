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

import com.mastfrog.util.strings.Strings;
import com.telenav.cactus.maven.git.GitCheckout;
import com.telenav.cactus.maven.log.BuildLog;
import com.telenav.cactus.maven.tree.ProjectTree;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 *
 * @author Tim Boudreau
 */
public abstract class ScopedCheckoutsMojo extends ScopeMojo
{
    public ScopedCheckoutsMojo()
    {
    }

    public ScopedCheckoutsMojo(boolean runFirst)
    {
        super(runFirst);
    }

    /**
     * Run this mojo against the project tree and set of checkouts it is applied
     * to.
     *
     * @param log A log
     * @param project The project being invoked agains
     * @param myCheckout The checkout of the project invoked against
     * @param tree The project tree of all checkouts
     * @param checkouts The checkouts which matched the scope and other
     * parameters of this mojo
     * @throws Exception if something goes wrong
     */
    protected abstract void execute(BuildLog log, MavenProject project,
            GitCheckout myCheckout, ProjectTree tree,
            List<GitCheckout> checkouts) throws Exception;

    /**
     * If this mojo should fail if any checkout it operates on is locally
     * modified, return true here.
     *
     * @return false by default
     */
    protected boolean forbidsLocalModifications()
    {
        return false;
    }

    @Override
    protected final void execute(BuildLog log, MavenProject project,
            GitCheckout myCheckout, Scope scope, ProjectFamily family,
            boolean includeRoot, boolean pretend) throws Exception
    {
        withProjectTree(tree ->
        {
            List<GitCheckout> checkouts = scope.matchCheckouts(tree,
                    myCheckout, includeRoot, projectFamily(), project
                            .getGroupId());

            log.ifDebug(() ->
            {
                log.debug(
                        "Operate on the following repositories for " + scope + ":");
                checkouts.forEach(co -> log.debug("  * " + co));
            });

            if (forbidsLocalModifications())
            {
                checkLocallyModified(checkouts);
            }

            execute(log, project, myCheckout, tree, checkouts);
        });
    }

    private void checkLocallyModified(Collection<? extends GitCheckout> coll)
            throws Exception
    {
        List<GitCheckout> modified = coll
                .stream()
                .filter(GitCheckout::isDirty)
                .collect(Collectors.toCollection(() -> new ArrayList<>(coll
                .size())));
        if (!modified.isEmpty())
        {
            String message = "Some checkouts are locally modified:\n"
                    + Strings.join("\n  * ", modified);
            throw new MojoExecutionException(this, message, message);
        }
    }
}
