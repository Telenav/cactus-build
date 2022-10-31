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

import com.mastfrog.concurrent.future.AwaitableCompletionStage;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converter interface specific to strings with convenience wrappers for
 * trimming, pattern matching and simply testing for output or not.
 */
public interface StringProcessResultConverter extends
        ProcessResultConverter<String>
{

    default ProcessResultConverter<Boolean> trueIfEmpty()
    {
        return testedWith(String::isEmpty);
    }

    default ProcessResultConverter<Boolean> testedWith(
            Predicate<String> predicate)
    {
        return map(predicate::test);
    }

    default ProcessResultConverter<List<String>> lines()
    {
        return trimmed().map(str ->
                str == null
                       ? null
                       : Arrays.asList(str.split("\n")));
    }

    default StringProcessResultConverter trimmed()
    {
        return (description, proc) ->
                AwaitableCompletionStage.of(onProcessStarted(description,
                        proc).thenApply(String::trim));
    }

    default StringProcessResultConverter filter(Pattern pattern)
    {
        return (description, proc) ->
        {
            CompletableFuture<String> result = new CompletableFuture<>();
            onProcessStarted(description, proc).whenComplete((str, thrown) ->
            {
                if (thrown != null)
                {
                    result.completeExceptionally(thrown);
                }
                else
                {
                    Matcher m = pattern.matcher(str);
                    assert m.groupCount() == 1;
                    if (m.find())
                    {
                        result.complete(m.group(1));
                    }
                    else
                    {
                        result.completeExceptionally(new IllegalStateException(
                                "Pattern " + pattern.pattern() + " not matched in '" + str + "'"));
                    }
                }
            });
            return AwaitableCompletionStage.of(result);
        };
    }
}
