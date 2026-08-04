/*
 * Copyright 2026 jsfmytg (github.com/bszapp)
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
 *
 * Modifications copyright (C) 2026 Your Name
 * This file has been modified to adapt to the YunX project, including
 * package rename and resource reference changes.
 */

/*
 * Modified for YunX project
 * Original source: com.wifi.toolbox.ui.items.CustomFabMenu
 */
package com.yunx.app.ui.items

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.R

data class FabMenuItem(
    val label: String,
    val icon: ImageVector,
    val isSelected: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun BoxScope.CustomFabMenu(
    expanded: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    items: List<FabMenuItem>,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val focusRequester = remember { FocusRequester() }
    val closeMenuActionLabel = stringResource(R.string.close_menu)

    Column(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom
    ) {
        // 菜单项区域
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items.forEachIndexed { i, item ->
                    // 第一个项上方增加一点间距，模拟原 FloatingActionButtonMenu 的 4dp 间隔
                    if (i == 0) {
                        Spacer(Modifier.height(4.dp))
                    }

                    Surface(
                        onClick = {
                            item.onClick()
                            onCheckedChange(false)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (item.isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .height(42.dp)
                            .semantics {
                                isTraversalGroup = true
                                if (i == items.size - 1) {
                                    customActions = listOf(
                                        CustomAccessibilityAction(label = closeMenuActionLabel) {
                                            onCheckedChange(false); true
                                        }
                                    )
                                }
                            }
                            .then(
                                if (i == 0) {
                                    Modifier.onKeyEvent {
                                        if (it.type == KeyEventType.KeyDown &&
                                            (it.key == Key.DirectionUp || (it.isShiftPressed && it.key == Key.Tab))
                                        ) {
                                            focusRequester.requestFocus()
                                            return@onKeyEvent true
                                        }
                                        false
                                    }
                                } else Modifier
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.label,
                                style = if (item.isSelected) MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                ) else MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    // 每个项之后添加 4dp 间距（最后一个项之后不再添加）
                    if (i < items.size - 1) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // 主 FAB 按钮
        AnimatedVisibility(
            visible = visible || expanded,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            FloatingActionButton(
                onClick = { onCheckedChange(!expanded) },
                modifier = Modifier
                    .size(48.dp)
                    .focusRequester(focusRequester)
                    .semantics { traversalIndex = -1f },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Crossfade(
                    targetState = expanded,
                    animationSpec = tween(200),
                    label = "fab icon"
                ) { isExpanded ->
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (isExpanded) stringResource(R.string.close_menu) else stringResource(R.string.open_menu),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}