/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nebula.plugin.release.git.semver

import com.github.zafarkhaja.semver.Version
import groovy.transform.CompileDynamic
import nebula.plugin.release.git.command.GitReadOnlyCommandUtil
import nebula.plugin.release.git.model.TagRef
import nebula.plugin.release.git.base.TagStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Locates the nearest {@link nebula.plugin.release.git.model.TagRef}s whose names can be
 * parsed as a {@link com.github.zafarkhaja.semver.Version version}. Both the
 * absolute nearest version tag and the nearest "normal version" tag are
 * included.
 *
 * <p>
 *   Primarily used as part of version inference to determine the previous
 *   version.
 * </p>
 *
 * @since 0.8.0
 */
@CompileDynamic
class NearestVersionLocator {
    private static final Logger logger = LoggerFactory.getLogger(NearestVersionLocator)
    private static final Version UNKNOWN = Version.valueOf('0.0.0')

    final TagStrategy strategy
    final GitReadOnlyCommandUtil gitCommandUtil

    NearestVersionLocator(GitReadOnlyCommandUtil gitCommandUtil, TagStrategy strategy) {
        this.strategy = strategy
        this.gitCommandUtil = gitCommandUtil
    }

    /**
     * Locate the nearest version in the given repository
     * starting from the current HEAD.
     *
     * <p>
     * All tag names are parsed to determine if they are valid
     * version strings. Tag names can begin with "v" (which will
     * be stripped off).
     * </p>
     *
     * <p>
     * The nearest tag is determined by getting a commit log between
     * the tag and {@code HEAD}. The version tag with the smallest
     * log from a pure count of commits will have its version returned. If two
     * version tags have a log of the same size, the versions will be compared
     * to find the one with the highest precedence according to semver rules.
     * For example, {@code 1.0.0} has higher precedence than {@code 1.0.0-rc.2}.
     * For tags with logs of the same size and versions of the same precedence
     * it is undefined which will be returned.
     * </p>
     *
     * <p>
     * Two versions will be returned: the "any" version and the "normal" version.
     * "Any" is the absolute nearest tagged version. "Normal" is the nearest
     * tagged version that does not include a pre-release segment.
     * </p>
     *
     * Defaults to {@code HEAD}.
     * @return the version corresponding to the nearest tag
     */
    NearestVersion locate() {
        logger.debug('Locate beginning on branch: {}', gitCommandUtil.currentBranch())
        // Reuse a single walk to make use of caching.
        List<String> tagRefs = gitCommandUtil.refTags()
        List allTags = tagRefs.collect { ref ->
            TagRef.fromRef(ref)
        }.findAll {
            it.version
        }

        List normalTags = allTags.findAll { !it.version.preReleaseVersion }
        def normal = findNearestVersion(normalTags)
        def any = findNearestVersion(allTags)

        logger.debug('Nearest release: {}, nearest any: {}.', normal, any)
        return new NearestVersion(any.version, normal.version, any.distance, normal.distance)
    }

    private Map findNearestVersion(List<TagRef> tagList) {
        List<Map> tagsWithDistance = tagList.collect { TagRef tag ->
            getTagWithDistance(tag)
        }
        if (tagsWithDistance) {
            tagsWithDistance.sort {}
            return tagsWithDistance.min { a, b ->
                def distanceCompare = a.distance <=> b.distance
                def versionCompare =  (a.version <=> b.version) * -1
                distanceCompare == 0 ? versionCompare : distanceCompare
            }
        } else {
            return [version: UNKNOWN, distance: gitCommandUtil.getCommitCountForHead()]
        }
    }

    private getTagWithDistance(TagRef tag) {
        try {
            String result = gitCommandUtil.describeTagForHead(tag.name)
            if(!result) {
                return [version: UNKNOWN, distance: gitCommandUtil.getCommitCountForHead()]
            }

            String[] parts = result.split('-')
            if(parts.size() < 3) {
                return [version: tag.version, distance: 0]
            }
            return [version: tag.version, distance: parts[parts.size() - 2]?.toInteger()]
        } catch (Exception e) {
            return [version: UNKNOWN, distance: gitCommandUtil.getCommitCountForHead()]
        }
    }
}
