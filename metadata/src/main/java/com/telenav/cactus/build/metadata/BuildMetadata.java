////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// © 2011-2021 Telenav, Inc.
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

package com.telenav.cactus.build.metadata;

import static com.telenav.cactus.build.metadata.BuildName.toBuildNumber;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.Collections.emptyMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Metadata about the calling KivaKit project as well as a program entrypoint that creates this information when called
 * from maven during the build process.
 *
 * @author jonathanl (shibo)
 */
public class BuildMetadata
{
    /**
     * @deprecated Use BuildName.KIVAKIT_EPOCH_DAY
     */
    @Deprecated
    public static final int KIVAKIT_EPOCH_DAY = BuildName.KIVAKIT_EPOCH_DAY;
    public static final String KEY_BUILD_NAME = "build-name";
    public static final String KEY_BUILD_DATE = "build-date";
    public static final String KEY_BUILD_NUMBER = "build-number";
    public static final String KEY_GIT_COMMIT_TIMESTAMP = "commit-timestamp";
    public static final String KEY_GIT_COMMIT_HASH = "commit-long-hash";
    public static final String KEY_GIT_REPO_CLEAN = "no-local-modifications";

    /** Metadata for projects */
    private static final Map<Class<?>, BuildMetadata> projectToMetadata = new ConcurrentHashMap<>();

    /**
     * @return The build number for the given date in days since {@link #KIVAKIT_EPOCH_DAY}
     */
    public int currentBuildNumber()
    {
        return toBuildNumber(currentBuildDate());
    }
    
    /**
     * @param projectType A class in the caller's project for loading resources
     * @return Metadata for the given project
     */
    public static BuildMetadata of(Class<?> projectType)
    {
        return projectToMetadata.computeIfAbsent(projectType, ignored -> new BuildMetadata(projectType, Type.PROJECT, emptyMap()));
    }

    /**
     * The type of metadata. PROJECT specifies normal project metadata. CURRENT specifies the metadata based on the
     * current time.
     *
     * @author jonathanl (shibo)
     */
    public enum Type
    {
        PROJECT,
        CURRENT
    }

    /** A class in the caller's project for loading resources */
    private final Class<?> projectType;

    /** The type of metadata */
    private final Type type;

    /** Build property map */
    Map<String, String> buildProperties;

    /** Project property map */
    private Map<String, String> projectProperties;
    private final Map<String,String> additional;

    BuildMetadata(Class<?> projectType, Type type, Map<String, String> additional)
    {
        this.projectType = projectType;
        this.type = type;
        this.additional = additional;
    }

    /**
     * Retrieves the properties in the /build.properties resource, similar to this:
     *
     * <pre>
     * build-number = 104
     * build-date = 2021.03.18
     * build-name = sparkling piglet
     * </pre>
     *
     * @return The contents of the maven metadata file
     */
    public Map<String, String> buildProperties()
    {
        if (buildProperties == null)
        {
            // If we are metadata for the current build,
            if (type == Type.CURRENT)
            {
                // then use current build metadata based on the time
                var properties = new TreeMap<String, String>();
                properties.put(KEY_BUILD_NUMBER, Integer.toString(currentBuildNumber()));
                properties.put(KEY_BUILD_DATE, DateTimeFormatter.ofPattern("yyyy.MM.dd").format(currentBuildDate()));
                properties.put(KEY_BUILD_NAME, BuildName.name(currentBuildNumber()));
                properties.putAll(additional);
                buildProperties = properties;
            }
            else
            {
                // otherwise, use the project's metadata.
                buildProperties = properties(metadata(projectType, "/build.properties"));
                buildProperties.putAll(additional);
            }
        }

        return buildProperties;
    }

    /**
     * Retrieves the properties in the /project.properties resource, similar to this:
     *
     * <pre>
     * project-name        = KivaKit - Application
     * project-version     = 1.3.5
     * project-group-id    = com.telenav.kivakit
     * project-artifact-id = kivakit-application
     * </pre>
     * <p>
     * This properties file should be generated by the maven build.
     *
     * @return The contents of the maven metadata file
     */
    public Map<String, String> projectProperties()
    {
        if (projectProperties == null)
        {
            projectProperties = properties(metadata(projectType, "/project.properties"));
        }

        return projectProperties;
    }

    LocalDate currentBuildDate()
    {
        return gitCommitTimestamp().map(
                zdt -> isCleanRepo() ? zdt.toLocalDate() : todaysLocalDate())
                .orElse(todaysLocalDate());
    }
    
    static LocalDate todaysLocalDate() 
    {
        return LocalDateTime.now().atZone(ZoneId.of(ZoneOffset.UTC.getId())).toLocalDate();
    }

    /**
     * @return The contents of the metadata resource at the given path
     */
    private static String metadata(Class<?> project, String path)
    {
        try
        {
            var input = project.getResourceAsStream(path);
            return input == null ? null : new BufferedReader(new InputStreamReader(input))
                    .lines()
                    .collect(Collectors.joining("\n"))
                    .trim();
        }
        catch (Exception cause)
        {
            throw new IllegalStateException("Unable to read: " + path, cause);
        }
    }

    /**
     * @return A properties map from the given text
     */
    static Map<String, String> properties(String text)
    {
        var properties = new TreeMap<String, String>();
        try
        {
            var pattern = Pattern.compile("(?x) (?<key> [\\w-]+?) \\s* = \\s* (?<value> .*)");
            var matcher = pattern.matcher(text);
            while (matcher.find())
            {
                properties.put(matcher.group("key"), matcher.group("value"));
            }
        }
        catch (Exception ignored)
        {
        }
        return properties;
    }

    /**
     * Get the git commit hash at the time the code was built, if present.
     * 
     * @return A hash if one is available in the metadata
     */
    public Optional<String> gitCommitHash()
    {
        return Optional.ofNullable(buildProperties().get(KEY_GIT_COMMIT_HASH));
    }

    /**
     * Get the short 7-character version of the git commit hash, which git itself
     * emits in some cases.
     * 
     * @return A short commit hash if present
     */
    public Optional<String> shortGitCommitHash() 
    {
        return gitCommitHash().map(hash -> hash.substring(0, 7));
    }
    
    /**
     * Determine whether or not the library was build against locally modified
     * sources, or if you can trust that building the git commit hash indicated
     * by this metadata will get you the same bits (assuming other libraries
     * are also the same bits).
     * 
     * @return True if the repo was definitely clean at build time.
     */
    public boolean isCleanRepo()
    {
        Map<String, String> map = buildProperties == null
                ? additional
                : buildProperties;
        return "true".equals(map.get(KEY_GIT_REPO_CLEAN));
    }

    /**
     * Get the timestamp of the git commit that originated the library this
     * metadata is for, if recorded.
     * 
     * @return A git timestamp, if present.
     */
    public Optional<ZonedDateTime> gitCommitTimestamp()
    {
        // We can be called while computing the properties, before buildProperties
        // is set, in which case we need to look in additional instead.
        Map<String, String> map = buildProperties == null ? additional: buildProperties;
        return Optional.ofNullable(map.get(KEY_GIT_COMMIT_TIMESTAMP))
                .map(dateString -> ZonedDateTime.parse(dateString, 
                        ISO_DATE_TIME));
    }
}
