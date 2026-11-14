package dev.macodes.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Design system token: All app-wide spacing and corner radii are now uniformly derived from this source, eliminating scattered hard-coded values.
 *
 * Spacing is based on a 4dp grid: xs=4, sm=8, md=12, lg=16, xl=24, xxl=32.
 */
object Spacing {
    val None = 0.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
}

/** Tokens with corner radius. */
object Corners {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Full = 999.dp
}

/** Component-sized tokens. */
object Sizes {
    /** Use a thin border throughout. */
    val BorderThin = 1.dp

    /** Compact header content height (excluding status bar padding). */
    val HeaderCompact = 48.dp

    /** List item status circles. */
    val StatusDot = 10.dp

    /** Settings page avatar. */
    val Avatar = 72.dp

    /** Generic small icons. */
    val IconSmall = 18.dp

    /** Generic icon / compact loading indicator. */
    val IconMedium = 24.dp

    /** Minimum touch target. */
    val TouchTarget = 48.dp

    /** Minimum height of form input fields, with alignment with Material 3 OutlinedTextField. */
    val FormFieldMinHeight = 64.dp

    /** Host card menu width: Accommodate session time and status while ensuring the pop-up layer does not occupy most of the card. */
    val HostActionsMenuWidth = 240.dp

    /** Set a fixed height for the Agent remote directory selector; allow the list to scroll internally, and ensure the confirmation button remains visible at all times. */
    val AgentDirectorySheet = 400.dp

    /** The Agent workspace sidebar should occupy a ratio of the screen width that preserves the main UI to maintain the drawer-style hierarchy. */
    val AgentDrawerWidthFraction = 0.7f

    /** Agent avatar. */
    val AgentAvatar = 32.dp

    /** Max width of an attachment chip in the Agent input area; excessively long file names are ellipsized internally. */
    val AgentAttachmentChipMaxWidth = 240.dp

    /** Max height of the slash-command candidate area in the Agent input field. */
    val AgentSlashMenuMaxHeight = 224.dp

    /** Agent tool timeline track width and line width. */
    val AgentTimelineRailWidth = 20.dp
    val AgentTimelineLineWidth = 2.dp
    val AgentTimelineDot = 8.dp

    /** Agent: A container for standardized icons on the time line. */
    val AgentActivityIconContainer = 36.dp

    /** User message bubbles occupy no more than a certain ratio of the conversation area’s width; short messages still adapt to fit the content. */
    val AgentUserBubbleMaxWidthFraction = 0.86f

    /** Agent responds with a three-dot indicator indicating the task is in progress. */
    val AgentTypingDot = 6.dp

    /** The range on the Agent conversation page where a back gesture triggers from the left edge of the screen. */
    val AgentBackGestureEdge = 32.dp

    /** The minimum horizontal scroll distance required to go back to the Agent conversation page by swiping right. */
    val AgentBackGestureThreshold = 72.dp

    /** When there is no Header element, this content space is reserved for the top floating navigation buttons. */
    val AgentChromeClearance = 56.dp
}

/** Remote screen floating keyboard / virtual mouse size. */
object ScreenControlDimens {
    val PermissionBannerBottom = 48.dp
    val PermissionBannerSide = 24.dp
    val PermissionBannerCorner = 8.dp
    val PermissionBannerHorizontal = 12.dp
    val PermissionBannerVertical = 6.dp

    val FloatingButton = 44.dp
    val FloatingIcon = 24.dp
    val FloatingMargin = 14.dp
    val FloatingSpacing = 8.dp

    val MousePanelWidth = 128.dp
    val MousePanelHeight = 158.dp
    val MouseTopButtonsHeight = 76.dp
    val MouseScrollWidth = 38.dp
    val MouseScrollHeight = 98.dp
    val MouseScrollTopOffset = (-6).dp
    val MouseCloseButton = 32.dp
    val MouseCloseGap = 6.dp
    val MouseCursorWidth = 22.dp
    val MouseCursorHeight = 26.dp
    val MouseCursorPanelOffsetX = 26.dp
    val MouseCursorPanelOffsetY = 8.dp
    val MousePanelGripHeight = 40.dp
    val MouseScrollStep = 32.dp
    val MouseDivider = 1.dp
    val MouseStroke = 1.dp
    val MouseScrollLine = 3.dp
    val MouseGripDot = 3.dp
}
