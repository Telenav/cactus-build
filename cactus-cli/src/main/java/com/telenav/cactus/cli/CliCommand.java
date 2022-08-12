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
package com.telenav.cactus.cli;

import com.telenav.cactus.cli.nuprocess.internal.ProcessCallback;
import com.mastfrog.concurrent.future.AwaitableCompletionStage;
import com.mastfrog.function.optional.ThrowingOptional;
import com.telenav.cactus.cli.nuprocess.ProcessControl;
import com.telenav.cactus.cli.nuprocess.ProcessResult;
import com.telenav.cactus.util.PathUtils;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A utility program we need to run, which takes care of the general ugliness of
 * java process management, and converting output into a usable object.
 *
 * @author Tim Boudreau
 */
@SuppressWarnings("unused")
public abstract class CliCommand<T> implements Supplier<String>
{
    public static AwaitableCompletionStage<ProcessResult> completionStageForProcess(
            ProcessControl proc)
    {
        return AwaitableCompletionStage.of(proc.onExit());
    }

    public static CliCommand<String> fixed(String command, Path workingDir,
            String... fixedArgs)
    {
        return new SimpleCommand(command, workingDir, fixedArgs);
    }

    static class SimpleCommand extends CliCommand<String>
    {
        private final Path workingDir;

        private final String[] fixedArgs;

        public SimpleCommand(String name, Path workingDir, String... fixedArgs)
        {
            super(name, new StringProcessResultConverterImpl());
            this.workingDir = workingDir;
            this.fixedArgs = fixedArgs;
        }

        @Override
        protected void configureArguments(List<String> list)
        {
            list.addAll(Arrays.asList(fixedArgs));
        }

        @Override
        protected Optional<Path> workingDirectory()
        {
            return Optional.ofNullable(workingDir);
        }
    }

    protected final String name;

    protected final ProcessResultConverter<T> resultCreator;

    public CliCommand(String name, ProcessResultConverter<T> resultCreator)
    {
        this.name = name;
        this.resultCreator = resultCreator;
    }

    public String get()
    {
        return toString();
    }

    /**
     * The result converter to actually use when running the process - this can
     * be overridden to return a wrapper that can, say, detect an authentication
     * failure, authenticate and then retry, or similar.
     *
     * @return A converter
     */
    protected ProcessResultConverter<T> resultConverter()
    {
        return resultCreator;
    }

    public AwaitableCompletionStage<T> run()
    {
        return AwaitableCompletionStage.from(() ->
        {
            ThrowingOptional<ProcessControl> p = launch();
            if (!p.isPresent())
            {
                return CompletableFuture.failedStage(
                        new IOException("Could not find executable for " + this));
            }
            return resultConverter().onProcessStarted(this, p.get());
        });
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder(name);
        List<String> args = new ArrayList<>();
        configureArguments(args);
        for (String arg : args)
        {
            sb.append(' ').append(arg);
        }
        workingDirectory().ifPresent(dir
                -> sb.append(" (in ").append(dir).append(')'));
        return sb.toString();
    }

    /**
     * Append command-line arguments passed to the found binary to the list.
     *
     * @param list A list
     */
    protected abstract void configureArguments(List<String> list);

    /**
     * Do any customization of the process builder (env, etc) here.
     *
     * @param bldr A process builder
     */
    protected void configureProcessBulder(NuProcessBuilder bldr,
            ProcessControl callback)
    {
        // for subclasses
    }

    protected ThrowingOptional<ProcessControl> launch()
    {
        validate();
        return ThrowingOptional.from(PathUtils.findExecutable(name)).map(path ->
        {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(path.toString());
            configureArguments(commandLine);

            ProcessCallback callback = new ProcessCallback();

            NuProcessBuilder pb = new NuProcessBuilder(commandLine);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.setProcessListener(callback);

            internalConfigureProcessBuilder(pb, callback);
            onLaunch(callback);

            NuProcess proc = pb.start();

            return callback;
        });
    }

    /**
     * Override to log process start or similar.
     *
     * @param proc A process
     */
    protected void onLaunch(ProcessControl proc)
    {
    }

    /**
     * Throw here if the command is misconfigured and cannot be run.
     */
    protected void validate()
    {
        // do nothing
    }

    protected Optional<Path> workingDirectory()
    {
        return Optional.empty();
    }

    private void internalConfigureProcessBuilder(NuProcessBuilder bldr,
            ProcessCallback callback)
    {
        workingDirectory().ifPresent(dir ->
        {
            bldr.setCwd(dir);
        });
        configureProcessBulder(bldr, callback);
    }
}
