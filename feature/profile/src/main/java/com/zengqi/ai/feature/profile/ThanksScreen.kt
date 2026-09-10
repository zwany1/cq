package com.zengqi.ai.feature.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 单个感谢对象的数据模型。
 */
data class Sponsor(
    val name: String,
    val message: String,
    val avatarRes: Int? = null,
    val avatarUrl: String? = null,
    val rank: Int = 0,
    val tint: Color = Color(0xFF8B5CF6)
)

/**
 * 感谢名单（排名不分先后）。
 * 头像资源由 E:\头像\avatars_named 自动生成，前六位为指定展示。
 */
internal val sponsors: List<Sponsor> = emptyList()


@Composable
fun ThanksScreen(
    onNavigateBack: () -> Unit,
    onViewFullList: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { isVisible = true }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 全屏背景图：等比例铺满屏幕，延伸至状态栏下方，消除左右空白
        Image(
            painter = painterResource(id = R.drawable.thanks_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 名单列表：放置在画面中下方（用户圈定的空白区域），避免与顶部返回按钮重叠
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val listTopOffset = maxHeight * 0.45f
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = listTopOffset),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 32.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    sponsors.take(6),
                    key = { _, item -> item.name }
                ) { index, sponsor ->
                    SponsorItem(
                        sponsor = sponsor,
                        index = index,
                        isVisible = isVisible
                    )
                }

                if (sponsors.size > 6) {
                    item { ViewFullListButton(onClick = onViewFullList) }
                }

                item { FooterNote() }
            }
        }

        // 左上角圆形返回按钮：玻璃质感 + 粉色主题，明显区别于其他页面的长条标题栏
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, top = 12.dp)
                .size(42.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFFF4A6B5),
                    spotColor = Color(0xFFF4A6B5)
                )
                .background(
                    color = Color.White.copy(alpha = 0.88f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color(0xFFE85D75),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ThanksFullListScreen(
    onNavigateBack: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { isVisible = true }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 全屏背景图
        Image(
            painter = painterResource(id = R.drawable.thanks_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 完整名单
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 32.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(
                sponsors,
                key = { _, item -> item.name }
            ) { index, sponsor ->
                SponsorItem(
                    sponsor = sponsor,
                    index = index,
                    isVisible = isVisible
                )
            }

            item { FooterNote() }
        }

        // 左上角圆形返回按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, top = 12.dp)
                .size(42.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFFF4A6B5),
                    spotColor = Color(0xFFF4A6B5)
                )
                .background(
                    color = Color.White.copy(alpha = 0.88f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color(0xFFE85D75),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SponsorItem(
    sponsor: Sponsor,
    index: Int,
    isVisible: Boolean
) {
    val animated by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(400, delayMillis = 200 + index * 80),
        label = "sponsor_item_$index"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = animated }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0xFF8B5CF6).copy(alpha = 0.3f),
                    spotColor = Color(0xFF8B5CF6).copy(alpha = 0.3f)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 头像
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(sponsor.tint.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (sponsor.avatarRes != null) {
                        Image(
                            painter = painterResource(id = sponsor.avatarRes),
                            contentDescription = sponsor.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = sponsor.name.firstOrNull()?.toString() ?: "?",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp
                            ),
                            color = sponsor.tint
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 名字 + 留言
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sponsor.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFFE85D75).copy(alpha = 0.8f)
                        )
                    }
                    if (sponsor.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = sponsor.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            color = Color.Black.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewFullListButton(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "点击查看全部 →",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color.Black.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        text = "排名不分先后    名单持续更新",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        ),
        color = Color.Black.copy(alpha = 0.5f)
    )
}
