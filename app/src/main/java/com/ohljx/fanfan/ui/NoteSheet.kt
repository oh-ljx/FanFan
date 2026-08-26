package com.ohljx.fanfan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohljx.fanfan.data.NoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CommentSurface = Color(0xFFF7F7F7)
private val CommentInk = Color(0xFF171717)
private val CommentMuted = Color(0xFF858585)
private val CommentField = Color(0xFFECEDEC)
private val CommentDivider = Color(0xFFE5E5E5)

/**
 * 评论面板高度占屏幕的比例；翻翻/查看页压缩舞台时用同一比例，
 * 给竖屏照片留足空间。
 */
internal const val NotePanelHeightFraction = 0.44f

/**
 * 轻量评论层；位移由调用方和主内容共用的动画进度驱动。
 * 普通全屏浮层（非 Popup），面板直接延伸到手势条所在的屏幕最底部。
 */
@Composable
fun NoteSheet(
    notes: List<NoteEntity>,
    progress: Float,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onDelete: (NoteEntity) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    val density = LocalDensity.current
    val quietInteraction = remember { MutableInteractionSource() }

    BackHandler(onBack = onDismiss)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelHeight = maxHeight * NotePanelHeightFraction
        val panelHeightPx = with(density) { panelHeight.toPx() }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(maxHeight - panelHeight)
                .clickable(
                    interactionSource = quietInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelHeight)
                .graphicsLayer {
                    translationY = panelHeightPx * (1f - progress.coerceIn(0f, 1f))
                }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(CommentSurface)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = "${notes.size} 条评论",
                    color = CommentInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭评论",
                        tint = CommentInk,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            HorizontalDivider(color = CommentDivider)

            if (notes.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = "还没有评论",
                        color = CommentMuted,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 2.dp, end = 8.dp),
                            ) {
                                Text(
                                    text = note.text,
                                    color = CommentInk,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                )
                                Text(
                                    text = formatNoteTime(note.createdAt),
                                    color = CommentMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            IconButton(
                                onClick = { onDelete(note) },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "删除评论",
                                    tint = CommentMuted,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                        }
                        if (index < notes.lastIndex) {
                            HorizontalDivider(color = CommentDivider)
                        }
                    }
                }
            }

            HorizontalDivider(color = CommentDivider)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("写评论…", fontSize = 14.sp) },
                    maxLines = 3,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = CommentInk,
                        unfocusedTextColor = CommentInk,
                        cursorColor = PineColor,
                        focusedContainerColor = CommentField,
                        unfocusedContainerColor = CommentField,
                        focusedPlaceholderColor = CommentMuted,
                        unfocusedPlaceholderColor = CommentMuted,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 94.dp),
                )
                IconButton(
                    enabled = canSend,
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            draft = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PineColor.copy(alpha = if (canSend) 1f else 0.18f)),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "保存评论",
                        tint = Color.White.copy(alpha = if (canSend) 1f else 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun formatNoteTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))
