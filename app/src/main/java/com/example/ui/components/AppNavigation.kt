package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.bidi.BidiEngine

// At this many tabs (or more) the label text is dropped from the bottom bar and only the icon is
// drawn, with the full label kept as a spoken description on the item. Measured on emulator-5554
// (1080x1920, 480dpi, 360dp wide): healthy nav labels need 104-133px of width
// (docs/10_UI_FIX_PLAN.md P-3), while six weighted slots leave roughly 54dp = 162px per item, so a
// 10-character label wanting 189px gets squeezed and breaks character-by-character.
private const val IconOnlyLabelThreshold = 5

enum class AppScreen(
    val titleFa: String,
    val titleEn: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val stepIndex: Int? = null,
    val stepLabel: String? = null
) {
    IMPORT("ورود داده", "Ingestion", Icons.AutoMirrored.Filled.Input, Icons.AutoMirrored.Outlined.Input, "tab_import", 1, "گام ۱: ورود"),
    WORKSPACE("محیط کار", "Workspace", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "tab_workspace", 2, "گام ۲: کارگاه"),
    DISTILL("پالایش هوشمند", "AI Distill", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "tab_distill", 3, "گام ۳: پالایش"),
    EXPORT("صدور اسناد", "Export", Icons.Filled.FileDownload, Icons.Outlined.FileDownload, "tab_export", 4, "گام ۴: صدور"),
    LIBRARY("کتابخانه", "Library", Icons.Filled.Folder, Icons.Outlined.Folder, "tab_library", null, null),
    SETTINGS("تنظیمات", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings", null, null)
}

/**
 * A fluid, animated sliding-bubble navigation bar inspired by the motion video.
 * When a tab is selected or when a stage finishes, an elevated glowing pill bubble
 * slides smoothly with spring physics from the previous tab to the newly active tab.
 */
/**
 * A step is behind us when the workflow has moved past it, or when that step's own work is finished.
 * `EXPORT` is the last step, so nothing can ever move past it: without the second clause its dot
 * stayed grey forever, even after the document had been written.
 */
internal fun stepIsPassed(
    step: AppScreen,
    currentScreen: AppScreen,
    currentSessionExported: Boolean
): Boolean {
    val stepIndex = step.stepIndex ?: return false
    val currentIndex = currentScreen.stepIndex ?: 0
    return stepIndex < currentIndex || (step == AppScreen.EXPORT && currentSessionExported)
}

@Composable
fun AppBottomNavBar(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
    unlockedScreens: Set<AppScreen> = AppScreen.values().toSet(),
    hasActiveSession: Boolean = false,
    progressiveEnabled: Boolean = true,
    currentSessionExported: Boolean = false,
    // Required rather than defaulted on purpose: a call site that forgets it fails to compile
    // instead of quietly drawing Latin digits on a screen set to Persian digits.
    usePersianDigits: Boolean
) {
    val density = LocalDensity.current

    // The library tabs (fun shown in LibraryScreen.kt) use this same local helper shape: one place
    // in the composable decides whether a number is drawn with Persian digits, so nothing on this
    // bar can drift by itself.
    fun shown(value: String) = if (usePersianDigits) BidiEngine.toPersianDigits(value) else value

    // Progressive workflow list of visible tabs
    val visibleScreens = remember(progressiveEnabled, unlockedScreens) {
        if (progressiveEnabled) {
            AppScreen.values().filter { screen ->
                screen in unlockedScreens || screen == AppScreen.IMPORT || screen == AppScreen.LIBRARY || screen == AppScreen.SETTINGS
            }
        } else {
            AppScreen.values().toList()
        }
    }

    // Label mode for this pass: full label text while it still fits, icon-only from
    // IconOnlyLabelThreshold items onwards so a squeezed label can never wrap character-by-character.
    val showItemLabels = visibleScreens.size < IconOnlyLabelThreshold

    // Dynamic layout coordinate tracking for fluid bubble sliding
    val tabPositions = remember { mutableStateMapOf<AppScreen, Float>() }
    val tabWidths = remember { mutableStateMapOf<AppScreen, Float>() }

    val targetCenterX = tabPositions[currentScreen] ?: 0f
    val targetTabWidth = tabWidths[currentScreen] ?: 60f

    // Spring physics animation for horizontal sliding translation
    val animatedCenterX by animateFloatAsState(
        targetValue = targetCenterX,
        animationSpec = spring(
            dampingRatio = 0.72f, // Gentle fluid bounce
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "sliding_bubble_center_x"
    )

    // Dynamic bubble width adjustment (subtle squash & stretch)
    val animatedBubbleWidth by animateFloatAsState(
        targetValue = (targetTabWidth * 0.90f).coerceIn(48f, 92f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "sliding_bubble_width"
    )

    // The system navigation buttons draw over this bar, so the capsule keeps them as bottom padding.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .background(Color.Transparent)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp + navBarInset)
    ) {
        // Step Breadcrumb Tracker (Only shown if active workflow is in progress)
        if (progressiveEnabled && hasActiveSession) {
            WorkflowBreadcrumbIndicator(
                currentScreen = currentScreen,
                unlockedScreens = unlockedScreens,
                usePersianDigits = usePersianDigits,
                currentSessionExported = currentSessionExported
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Floating Pill Navigation Bar Container.
        // heightIn (not a fixed height) so that whatever the tabs genuinely measure makes the bar
        // taller instead of the text spilling out of a 66dp box and over the content above it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(33.dp),
                    ambientColor = Color(0x66000000),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                )
                .clip(RoundedCornerShape(33.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E293B), // Deep slate obsidian
                            Color(0xFF0F172A)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF334155).copy(alpha = 0.8f),
                            Color(0xFF64748B).copy(alpha = 0.4f),
                            Color(0xFF334155).copy(alpha = 0.8f)
                        )
                    ),
                    shape = RoundedCornerShape(33.dp)
                )
                .testTag("main_navigation_bar"),
            contentAlignment = Alignment.CenterStart
        ) {
            // 🌟 1. The Fluid Sliding Active Bubble Indicator (Hardware Accelerated via graphicsLayer)
            if (targetCenterX > 0f) {
                Box(
                    modifier = Modifier
                        .height(52.dp)
                        .graphicsLayer {
                            translationX = animatedCenterX - (animatedBubbleWidth / 2f)
                        }
                        .width(with(density) { animatedBubbleWidth.toDp() })
                        .shadow(
                            elevation = 10.dp,
                            shape = RoundedCornerShape(26.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            spotColor = MaterialTheme.colorScheme.primary
                        )
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF6366F1), // Electric Indigo
                                    Color(0xFF4F46E5),
                                    Color(0xFF06B6D4)  // Vibrant Cyan
                                )
                            )
                        )
                )
            }

            // 🌟 2. Interactive Navigation Tabs Layer.
            // Width-only fill: the tabs size the bar vertically (see heightIn above), so nothing in
            // here can be measured taller than the box that draws it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleScreens.forEach { screen ->
                    val selected = currentScreen == screen

                    // Completion state tracking
                    val isStepCompleted = when (screen) {
                        AppScreen.IMPORT -> hasActiveSession && currentScreen != AppScreen.IMPORT
                        AppScreen.WORKSPACE -> currentScreen == AppScreen.DISTILL || currentScreen == AppScreen.EXPORT
                        AppScreen.DISTILL -> currentScreen == AppScreen.EXPORT
                        else -> false
                    }

                    // Animated elevation and scale for active tab icon
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.15f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab_icon_scale"
                    )
                    val iconLift by animateFloatAsState(
                        targetValue = if (selected) -2f else 0f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab_icon_lift"
                    )

                    val contentColor by animateColorAsState(
                        targetValue = when {
                            selected -> Color.White
                            isStepCompleted -> Color(0xFF10B981)
                            else -> Color(0xFF94A3B8)
                        },
                        animationSpec = tween(220),
                        label = "tab_content_color"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // Same floor as the pill, so the touch target and the ripple stay as tall
                            // as they are today, while a taller tab content grows the whole bar.
                            .heightIn(min = 66.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.2f))
                            ) {
                                onScreenSelected(screen)
                            }
                            .onGloballyPositioned { coordinates ->
                                val center = coordinates.positionInParent().x + (coordinates.size.width / 2f)
                                tabPositions[screen] = center
                                tabWidths[screen] = coordinates.size.width.toFloat()
                            }
                            // Spoken label for every item: in icon-only mode it is the only label, and
                            // in label mode it survives an ellipsized "..." so the tab is still announced.
                            .semantics(mergeDescendants = true) {
                                contentDescription = screen.titleFa
                            }
                            .testTag(screen.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                                translationY = iconLift
                            }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (isStepCompleted) {
                                        Badge(
                                            containerColor = Color(0xFF10B981),
                                            contentColor = Color.White
                                        ) {
                                            Text("✓", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else if (screen.stepIndex != null && selected) {
                                        Badge(
                                            containerColor = Color.White.copy(alpha = 0.25f),
                                            contentColor = Color.White
                                        ) {
                                            Text(shown(screen.stepIndex.toString()), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    // Decorative: the tab's own semantics (added on the item Box above)
                                    // carries the spoken label, so the icon must not repeat it.
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (showItemLabels) {
                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = screen.titleFa,
                                    fontSize = if (selected) 10.5.sp else 9.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowBreadcrumbIndicator(
    currentScreen: AppScreen,
    unlockedScreens: Set<AppScreen>,
    usePersianDigits: Boolean,
    currentSessionExported: Boolean
) {
    // The rail is drawn outside AppBottomNavBar's body, so it wraps the same preference locally
    // rather than reaching into the caller.
    fun shown(value: String) = if (usePersianDigits) BidiEngine.toPersianDigits(value) else value

    val workflowSteps = listOf(
        AppScreen.IMPORT,
        AppScreen.WORKSPACE,
        AppScreen.DISTILL,
        AppScreen.EXPORT
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.7f))
            .border(0.5.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        workflowSteps.forEachIndexed { index, step ->
            val isUnlocked = step in unlockedScreens || step == AppScreen.IMPORT
            val isCurrent = currentScreen == step
            val isPassed = stepIsPassed(step, currentScreen, currentSessionExported)

            val stepColor by animateColorAsState(
                targetValue = when {
                    isCurrent && !isPassed -> MaterialTheme.colorScheme.primary
                    isPassed -> Color(0xFF10B981)
                    isUnlocked -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                },
                animationSpec = tween(300),
                label = "step_color"
            )

            // Each step gets an equal share of the row (capped at its natural width) so the last
            // step is never left with the scraps and wraps one character per line.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Step Dot / Number
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(stepColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPassed) {
                        Text("✓", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(
                            text = shown(step.stepIndex.toString()),
                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))
                // maxLines = 1 + Ellipsis is the guard: a step label that does not fit its share is
                // truncated (the full string still reaches TalkBack through the Text semantics node),
                // it never grows the row to ten one-character lines.
                Text(
                    text = step.titleFa,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 10.sp,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Connector line between steps
                if (index < workflowSteps.size - 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(
                                if (isPassed) Color(0xFF10B981) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }
    }
}
