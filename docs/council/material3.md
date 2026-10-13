# Kabuk vs Material 3: audit and recommendations

**Overall:** the bones are good: M3 1.3 components, a dark scheme, ModalBottomSheet, AlertDialog, Switch. The problems are mostly at the token level. The scheme is only half defined, so M3's baseline purple leaks into the UI. There are about 25 raw colors and about 40 ad-hoc `fontSize` overrides. Several surfaces are hand-built Rows where an M3 component exists. **One rule for the terminal path:** anything that changes the terminal's height must never animate (each frame would trigger a resize and a reflow). Only overlays animate.

Ranked, most impact first. Effort is S (small), M (medium) or L (large).

1. **The color scheme is half defined, so baseline purple leaks in (S).** `Theme.kt:25-30` sets 12 roles. Every role it leaves out falls back to M3 baseline values: `secondaryContainer #4A4458`, `primaryContainer #4F378B`, `surfaceContainerHighest #36343B`, `outlineVariant #49454F`, `tertiary #EFB8C8`. You can see this in `settings.png` (lavender slider inactive track, purple-grey Switch tracks) and in `text-mode.png` (lavender "Join lines" chip). Any future FAB or Badge would also come out purple. *M3: define every role, and pair each "on" role with its own container.* Fix: replace the scheme with the complete one at the end of this file.

2. **Hardcoded colors and top-level constants instead of roles (M).** Examples: `KeyBar.kt:120,144-149,194`, `AgentUi.kt:55,146`, `TerminalScreen.kt:141,254,266`, `PromptBox.kt:137-138,163`, `SettingsScreen.kt:71,130`, plus `Bg/Surface1/Accent/Muted` used directly in every file. Fix:
   - Read colors from `MaterialTheme.colorScheme`.
   - M3 has no success or warning roles. Add `data class KabukColors(success, warning, onSuccessContainer…)` behind `staticCompositionLocalOf`, exposed as `MaterialTheme.kabuk` via an extension.
   - Map the key bar: container `surfaceContainer`, key `surfaceContainerHigh`, pressed `surfaceContainerHighest`, ONCE `secondaryContainer`/`onSecondaryContainer`, LOCKED `primary`/`onPrimary`.

3. **Toasts should be Snackbars (M).** `MainActivity.kt:85` shows `Toast` for everything; `PromptBox.kt:80` and `TabManager.toast` are the sources. *M3: a Snackbar gives brief feedback with at most one action.* Fix:
   - Put a `SnackbarHost(hostState)` above the key bar (in `TerminalScreen`'s Column, or in a `Scaffold` on the other screens), and collect `TabManager.toasts` into `hostState.showSnackbar`.
   - Add actions where they help: "Not connected – text kept" → **Reconnect**; "Session ended" → **Undo** is not possible, so leave that one plain.
   - Colors come from the `inverseSurface`/`inverseOnSurface` roles.

4. **Quick-reply bar: use real chips and stop the terminal jump (M).** `AgentUi.kt:125-149` builds hand-made pills. *M3: suggestion chips are for dynamically generated replies, which is exactly this case.* Fix:
   - The first option ("1 Yes") is a `Button` with `contentPadding = PaddingValues(horizontal = 16.dp)` and height 40dp.
   - The other options are `SuggestionChip(label = { Text("2  Yes, don't ask") })` (32dp visual; 48dp touch target comes automatically from `minimumInteractiveComponentSize`).
   - Esc is an `AssistChip(leadingIcon = Icons.Close)` tinted `colorScheme.error`.
   - Spacing is `Arrangement.spacedBy(8.dp)` with 8dp horizontal padding.
   - To stop the height jump, overlay the bar on the terminal: put it in the terminal `Box` with `Modifier.align(BottomCenter)` on a `Surface(color = surfaceContainer.copy(alpha = .94f))`, and animate it with `AnimatedVisibility(enter = slideInVertically { it } + fadeIn(tween(200, easing = EmphasizedDecelerate)), exit = fadeOut(tween(150)))`. It costs no layout pass and causes no resize.
   - Caveat: the overlay covers the bottom terminal rows. Claude's menu usually sits there, so call `view.scrollBy` (or add matching bottom padding) once the bar appears.

5. **Line-height bug in the subtitles (S).** `Text(fontSize = 13.sp)` keeps the inherited `bodyLarge` line height of 24sp. That causes the loose double-spaced look in `settings.png` and `host-edit.png` (`HostsScreen.kt:236`, `SettingsScreen.kt` throughout). Fix: give `MaterialTheme` a `Typography` and use roles everywhere:

   | Use | Role |
   |---|---|
   | Screen titles | `titleLarge` (22sp) |
   | List and switch titles | `bodyLarge` (16/24) |
   | Supporting text | `bodyMedium` (14/20) in `onSurfaceVariant` |
   | Section headers | `titleSmall` in `primary`, sentence case rather than "LOOK"/"SERVERS" |
   | Chips and buttons | `labelLarge` |
   | Key bar | `labelLarge.copy(fontFamily = Mono)` |
   | Key corner hints | 10sp minimum (currently 8sp, `KeyBar.kt:194`, too small to read) |

   Roboto stays for the app chrome; JetBrains Mono is for data only (paths, keys, snippets).

6. **Prompt field styling and touch targets (M).** `PromptBox.kt:101-166`.
   - The hide (⌄) and ⚡ buttons are 36×48dp, under the 48dp minimum. Move ⚡ into the field's `leadingIcon`, which gives it a 48dp IconButton and more room for the text, and make ⌄ 48dp.
   - Use the messaging-style field: `TextField` with `shape = RoundedCornerShape(24.dp)` when compact (`extraLarge` when expanded) and `colors = TextFieldDefaults.colors(focusedContainerColor = surfaceContainerHigh, unfocusedContainerColor = surfaceContainerHigh, focusedIndicatorColor = Transparent, unfocusedIndicatorColor = Transparent)`.
   - Send stays a custom circle (it needs long-press), but use `colorScheme.primary`/`onPrimary` and add `semantics { onLongClick("Insert without Enter") { … } }`.
   - When expanded, the trailing icons sit vertically centred and mic/attach disappear (`prompt-expanded.png`). Put an action row (attach · mic · collapse) along the bottom edge of the field instead.

7. **Edge-to-edge is half done (S).** `enableEdgeToEdge()` is on, but `MainActivity.kt:98` pads the root with `systemBarsPadding()` on `Bg`. So the status bar shows `Bg` while the tab strip is `Surface1`, which leaves a visible seam. Fix:
   - Drop the root `systemBarsPadding()`.
   - Paint the TabStrip background first, then apply `windowInsetsPadding(WindowInsets.statusBars)`.
   - Do the same for the bottom bar with `WindowInsets.navigationBars.union(WindowInsets.ime)`.
   - Other screens use `Scaffold` and its `innerPadding`.

8. **Hosts screen (M).** `HostsScreen.kt:64-135`.
   - Use `Scaffold` + `TopAppBar` with the nautilus logo as `navigationIcon` and Settings in `actions`.
   - Replace the "Add server" outlined button with an `ExtendedFloatingActionButton(icon = Add, text = "Add server")`. It is reachable one-handed and colored `primaryContainer`.
   - Replace the custom `Card` with `Card(onClick, colors = CardDefaults.cardColors(surfaceContainerLow), shape = MaterialTheme.shapes.medium)` wrapping a `ListItem(headlineContent, supportingContent, leadingContent, trailingContent = IconButton(Edit), colors = ListItemDefaults.colors(containerColor = Transparent))`.
   - Add a live state badge (Connected / n agents) to fill the empty screen.

9. **Settings (M).** `SettingsScreen.kt`.
   - Use `Scaffold` + `TopAppBar(scrollBehavior = pinnedScrollBehavior())`.
   - Each toggle is a `ListItem` with a trailing `Switch(onCheckedChange = null)`; put `Modifier.toggleable(role = Role.Switch)` on the whole row.
   - Scroll speed and scrollback are mutually exclusive choices, so use `SingleChoiceSegmentedButtonRow` + `SegmentedButton` instead of FilterChips (`:111,118`). *M3: filter chips filter content.*
   - The font slider's 15 ticks are noisy: use `steps = 0` and round the value.
   - Theme swatches: `selectable(role = Role.RadioButton)`, a 2dp `primary` border and a check icon on the selected one.
   - "New key" should be `OutlinedButton(colors = outlinedButtonColors(contentColor = error))`. "Clear history" should be a `TextButton`.

10. **Host edit screen (S).**
    - Colour dots are 30dp with no selection indicator (`HostsScreen.kt:211`). Make them 40dp with a 48dp target, `Icons.Check` on the selected one, and `selectable` semantics.
    - The delete button is `Surface2` with red text (`:217`). Use `OutlinedButton` with the `error` role.
    - The password field needs a visibility toggle (`trailingIcon`).
    - Add `imeAction = ImeAction.Next` to each field.
    - The top bar becomes a `TopAppBar` with Save in `actions`.

11. **Copy-text screen (M).** The `TextModeScreen.kt:69-84` header crams a two-line title, a chip and three icons into 52dp. Move the actions into a `BottomAppBar(actions = { IconToggleButton(join); IconToggleButton(wrap); IconButton(copyAll) }, floatingActionButton = { FloatingActionButton(share) })`. That makes them thumb-reachable, and the top bar becomes a plain `TopAppBar` with title and subtitle. Toggled icons use `FilledTonalIconToggleButton`.

12. **Agents chip and tab strip (S).**
    - `AgentsChip` (`AgentUi.kt:53`) becomes an `AssistChip`. When something needs you, use `tertiaryContainer`/`onTertiaryContainer` (the logo pink) instead of `Bad.copy(alpha = .22f)`.
    - The tab unread dot (`TerminalScreen.kt:269`) becomes `Badge()`.
    - The tab close button is 34dp (`:272`); add `Modifier.minimumInteractiveComponentSize()` around a 40dp target.
    - Strip height goes from 46dp to 48dp.
    - The selected tab uses `secondaryContainer`/`onSecondaryContainer` and `shapes.small`.

13. **Menus (S).** `DropdownMenu`s (`TerminalScreen.kt:196-230,276-291`) have no icons and use a raw `Bad` color. Fix:
    - Add `leadingIcon`s (ContentCopy, SelectAll, ContentPaste, TextIncrease/Decrease, Dns, Settings).
    - Destructive items use `colors = MenuDefaults.itemColors(textColor = error)`.
    - Use `shapes.small`.
    - The "Earlier" header (`PromptBox.kt:115`) uses `labelMedium` with `onSurfaceVariant`.

14. **Shapes (S).** The code uses magic radii 8/10/12/14/16/19dp. Fix:
    - Set `Shapes(extraSmall = 4.dp, small = 8.dp, medium = 12.dp, large = 16.dp, extraLarge = 28.dp)` and reference them.
    - Keys and tabs use `small`.
    - Cards, the disconnect banner and the selection bar use `medium`.
    - Chips and the send button use `CircleShape`/full.
    - Sheets and dialogs keep the default `extraLarge`.

15. **Disconnect banner (S).** `TerminalScreen.kt:336`.
    - Use `Surface(shape = medium, color = errorContainer, contentColor = onErrorContainer)` for "connection lost". The current neutral grey reads as info rather than a problem.
    - The actions are `TextButton`s in `onErrorContainer`.
    - Animate it in with `AnimatedVisibility(slideInVertically { -it } + fadeIn())`. It's an overlay, so it's safe to animate.

16. **Selection bar (S).** `TerminalScreen.kt:353`: five TextButtons plus Close nearly overflow 412dp. Make it a `Surface(shape = CircleShape, color = surfaceContainerHigh, shadowElevation = 3.dp)` "floating toolbar" with icon buttons that each have a tooltip (Copy, As shown, Wide, All, Paste). M3 Expressive's `HorizontalFloatingToolbar` needs material3 1.4+; the BOM is 1.3.1.

17. **Motion worth adding, all cheap and none touching terminal size (S).**
    - Jump-to-bottom FAB: `AnimatedVisibility(scaleIn() + fadeIn())`, with default `secondaryContainer` colors instead of the overrides at `TerminalScreen.kt:141`.
    - Tab items: `Modifier.animateItem()` in the LazyRow.
    - Agents chip text: `AnimatedContent`.
    - Status dots: `animateColorAsState`.
    - Screen changes (Hosts/Settings/Edit/Text) wrapped in `AnimatedContent(screen)` with a shared-axis X transition (`slideInHorizontally { it / 10 } + fadeIn(300ms Emphasized)`). Terminal ↔ others crossfade only.
    - The prompt box appears and disappears with fade only. No `expandVertically`, so the terminal resizes once and does not animate.

18. **Dialogs (S).** `Dialogs.kt:36`: the host-key danger dialog should get `icon = { Icon(Icons.Warning) }`, `titleContentColor = error`, and the confirm button in `error`. Remove `fontSize = 18.sp` (`:55`); the dialog title is already `headlineSmall`.

19. **Dynamic color: offer it, off by default (S).** Add a "Match wallpaper colors" switch, available on API 31+. When it's on, use `dynamicDarkColorScheme(ctx)` for the app chrome only. Terminal ANSI colors and `KabukColors` success/warning stay fixed. The brand peach is the default identity, and a terminal app benefits from a stable palette.

20. **Elevation (S).** Express depth through `surfaceContainer*` tiers rather than ad-hoc greys:

    | Element | Container role |
    |---|---|
    | Terminal | `surface` (#0B0E14) |
    | Tab strip and key bar | `surfaceContainer` |
    | Prompt field | `surfaceContainerHigh` |
    | Agents sheet | `surfaceContainerLow` (sheet default, drop `containerColor = Surface1`) |
    | Menus and dialogs | defaults |

## Proposed `Theme.kt` scheme

The brand peach becomes primary, the existing Tokyo-Night blue becomes secondary, and the logo pink becomes tertiary for attention ("needs you"). The surfaces are built up from #0B0E14.

```kotlin
private val KabukDark = darkColorScheme(
    primary = Color(0xFFFFC48A), onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF6B4019), onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF8A5A2B), surfaceTint = Color(0xFFFFC48A),
    secondary = Color(0xFF7AA2F7), onSecondary = Color(0xFF0A1F4D),
    secondaryContainer = Color(0xFF2A3B66), onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFFF7768E), onTertiary = Color(0xFF5C0F22),
    tertiaryContainer = Color(0xFF6E2335), onTertiaryContainer = Color(0xFFFFD9DF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0E14), onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0B0E14), onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF2A2F3B), onSurfaceVariant = Color(0xFFB4BCD0),
    surfaceDim = Color(0xFF0B0E14), surfaceBright = Color(0xFF30343F),
    surfaceContainerLowest = Color(0xFF06080C), surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A22), surfaceContainerHigh = Color(0xFF1F2430),
    surfaceContainerHighest = Color(0xFF2A2F3B),
    outline = Color(0xFF7F8AA3), outlineVariant = Color(0xFF2E3440),
    inverseSurface = Color(0xFFE6E9EF), inverseOnSurface = Color(0xFF1F2430),
    scrim = Color(0xFF000000),
)

@Immutable data class KabukColors(
    val success: Color = Color(0xFF9ECE6A), val warning: Color = Color(0xFFE0AF68),
    val danger: Color = Color(0xFFF7768E), // terminal/status red; M3 `error` stays for UI errors
)
val LocalKabukColors = staticCompositionLocalOf { KabukColors() }

@Composable
fun KabukTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx) else KabukDark
    CompositionLocalProvider(LocalKabukColors provides KabukColors()) {
        MaterialTheme(colorScheme = scheme, typography = KabukTypography, shapes = KabukShapes, content = content)
    }   // drop the manual LocalContentColor override; MaterialTheme/Surface supply it
}
```

**Contrast check:** primary #FFC48A on surface is about 12:1. onSurfaceVariant #B4BCD0 on #161A22 is about 9:1. outline #7F8AA3 on #0B0E14 is about 5.6:1, which meets the 3:1 requirement for boundaries. The send button changes from blue to peach. If the user prefers blue as the main accent, swap the primary and secondary blocks; nothing else needs to change.
