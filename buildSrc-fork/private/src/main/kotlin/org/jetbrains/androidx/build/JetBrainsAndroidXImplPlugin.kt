/*
 * Copyright 2024 The Android Open Source Project
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

@file:Suppress("unused")

package org.jetbrains.androidx.build

import androidx.build.AndroidXMultiplatformExtension
import androidx.build.ProjectLayoutType.Companion.isJetBrainsFork
import javax.inject.Inject
import kotlinx.validation.ApiValidationExtension
import kotlinx.validation.ExperimentalBCVApi
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.apply
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper

class JetBrainsAndroidXImplPlugin @Inject constructor(
    val componentFactory: SoftwareComponentFactory
) : Plugin<Project> {

    @Suppress("UNREACHABLE_CODE", "UNUSED_VARIABLE")
    override fun apply(project: Project) {
        if (!isJetBrainsFork(project)) return

        project.configureTests()
        project.changeMavenCoordinatesToJetBrains()
//        project.configureRedirectionCapability() // TODO CMP-10368 fix old capability mechanism after migration to new artifact redirection
        project.configureMavenArtifactUpload(componentFactory)
        project.configureDependencyVerification()
        project.registerRedirectVersionsExtension()
        project.plugins.all { plugin ->
            if (plugin is KotlinMultiplatformPluginWrapper) {
                onKotlinMultiplatformPluginApplied(project)
            }
        }
    }

    private fun onKotlinMultiplatformPluginApplied(project: Project) {
        enableBinaryCompatibilityValidator(project)
        val multiplatformExtension =
            project.extensions.getByType(KotlinMultiplatformExtension::class.java)

        // Parallel-graph back-end: consume `redirect { }` target declarations and re-root each
        // redirect target onto an empty `redirectCommonMain` that depends on the androidx.* coord.
        project.extensions.findByType(AndroidXMultiplatformExtension::class.java)
            ?.let { mpe -> project.applyParallelRedirectGraph(multiplatformExtension, mpe) }
    }
}

private fun Project.configureTests() {
    tasks.withType(AbstractTestTask::class.java) { task ->
        task.testLogging.apply {
            events = hashSetOf(
                TestLogEvent.FAILED,
                TestLogEvent.SKIPPED,
                TestLogEvent.STANDARD_OUT,
                TestLogEvent.PASSED
            )
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

@OptIn(ExperimentalBCVApi::class)
private fun enableBinaryCompatibilityValidator(project: Project) {
    project.afterEvaluate {
        if (JetBrainsPublication.shouldPublish(project)) {
            if (!hostSupportsKlibAbiValidation()) {
                // The binary-compatibility-validator plugin resolves the Kotlin/Native host
                // target eagerly while being applied, so on hosts the Kotlin/Native toolchain
                // does not recognize it cannot even be applied.
                project.logger.warn(
                    "Skipping binary compatibility validation for ${project.path}: the " +
                        "Kotlin/Native toolchain does not recognize this host (see " +
                        "org.jetbrains.kotlin.konan.target.HostManager.host), which the " +
                        "binary-compatibility-validator plugin requires."
                )
                return@afterEvaluate
            }
            project.apply(plugin = "org.jetbrains.kotlinx.binary-compatibility-validator")
            project.extensions.getByType(ApiValidationExtension::class.java).apply {
                // The property allows running the JVM-only apiDump/apiCheck on hosts where the
                // Kotlin/Native toolchain cannot produce klibs.
                klib.enabled =
                    !project.providers
                        .gradleProperty("jetbrains.compose.disableKlibAbiValidation")
                        .isPresent
                nonPublicMarkers += NON_PUBLIC_MARKERS
            }
        }
    }
}

/**
 * Klib ABI validation resolves the Kotlin/Native host target eagerly when the
 * binary-compatibility-validator plugin is applied, which fails on hosts the Kotlin/Native
 * toolchain does not recognize (for example linux/aarch64). On such hosts the validation cannot
 * run at all, so it is skipped instead of failing the whole build.
 */
private fun hostSupportsKlibAbiValidation(): Boolean =
    try {
        org.jetbrains.kotlin.konan.target.HostManager.host
        true
    } catch (_: Throwable) {
        false
    }

// Not ideal to have a list instead of a pattern to match but this is all the API supports right now
// https://github.com/Kotlin/binary-compatibility-validator/issues/280
private val NON_PUBLIC_MARKERS =
    setOf(
        "androidx.annotation.Experimental",
        "androidx.compose.animation.ExperimentalAnimationApi",
        "androidx.compose.animation.ExperimentalSharedTransitionApi",
        "androidx.compose.animation.core.ExperimentalAnimatableApi",
        "androidx.compose.animation.core.ExperimentalAnimationSpecApi",
        "androidx.compose.animation.core.ExperimentalTransitionApi",
        "androidx.compose.animation.core.InternalAnimationApi",
        "androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
        "androidx.compose.foundation.gestures.ExperimentalTapGestureDetectorBehaviorApi",
        "androidx.compose.foundation.ExperimentalFoundationApi",
        "androidx.compose.foundation.InternalFoundationApi",
        "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        "androidx.compose.material.ExperimentalMaterialApi",
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.material3.ExperimentalMaterial3ComponentOverrideApi",
        "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "androidx.compose.runtime.ExperimentalComposeApi",
        "androidx.compose.runtime.ExperimentalComposeRuntimeApi",
        "androidx.compose.runtime.InternalComposeApi",
        "androidx.compose.runtime.InternalComposeTracingApi",
        "androidx.compose.ui.ExperimentalComposeUiApi",
        "androidx.compose.ui.InternalComposeUiApi",
        "androidx.compose.ui.input.pointer.util.ExperimentalVelocityTrackerApi",
        "androidx.compose.ui.node.InternalCoreApi",
        "androidx.compose.ui.test.ExperimentalTestApi",
        "androidx.compose.ui.test.InternalTestApi",
        "androidx.compose.ui.text.ExperimentalTextApi",
        "androidx.compose.ui.text.InternalTextApi",
        "androidx.compose.ui.unit.ExperimentalUnitApi",
        "androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi",
        "androidx.window.core.ExperimentalWindowApi",
    )
