package com.swiftwind.accelerator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.data.NodeSource
import com.swiftwind.accelerator.vpn.NodeHealth
import com.swiftwind.accelerator.ui.theme.Danger
import com.swiftwind.accelerator.ui.theme.Danger
import com.swiftwind.accelerator.ui.theme.Mint
import com.swiftwind.accelerator.ui.theme.MintDeep
import com.swiftwind.accelerator.ui.theme.Sky
import com.swiftwind.accelerator.ui.theme.Stroke
import com.swiftwind.accelerator.ui.theme.Surface as SurfaceColor
import com.swiftwind.accelerator.ui.theme.SurfaceHi
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.ui.theme.TextSecondary
import com.swiftwind.accelerator.viewmodel.UiState

@Composable
fun NodesScreen(
    ui: UiState,
    onFetchWarp: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImportText: (String) -> Unit,
    onAddSubscription: (String) -> Unit,
    onProbe: () -> Unit
) {
    var showImport by remember { mutableStateOf(false) }
    var showSubscription by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Node?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("节点", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            "选择线路，或一键获取免费节点",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(18.dp))
        WarpCard(busy = ui.busy, onFetch = onFetchWarp)

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的节点", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "  ${ui.nodes.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onProbe,
                enabled = !ui.busy && ui.nodes.isNotEmpty()
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Mint
                    )
                } else {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = Mint,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    if (ui.busy) "体检中" else "一键体检",
                    color = if (ui.nodes.isEmpty()) TextSecondary else Mint,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (ui.nodes.isEmpty()) {
            EmptyNodes(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ui.nodes, key = { it.id }) { node ->
                    NodeRow(
                        node = node,
                        selected = node.id == ui.selectedId,
                        health = ui.health[node.id] ?: NodeHealth.UNKNOWN,
                        onSelect = { onSelect(node.id) },
                        onDelete = { pendingDelete = node }
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { showImport = true },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("导入配置", modifier = Modifier.padding(start = 7.dp))
            }
            OutlinedButton(
                onClick = { showSubscription = true },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("添加订阅", modifier = Modifier.padding(start = 7.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入 WireGuard 配置") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 10,
                    placeholder = { Text("[Interface]\nPrivateKey = ...\n[Peer]\n...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportText(text)
                        showImport = false
                    },
                    enabled = text.isNotBlank()
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消") }
            }
        )
    }

    if (showSubscription) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSubscription = false },
            title = { Text("添加订阅") },
            text = {
                Column {
                    Text(
                        "支持返回 WireGuard 配置的 http/https 链接，可包含多个节点。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        placeholder = { Text("https://example.com/subscription") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddSubscription(url)
                        showSubscription = false
                    },
                    enabled = url.isNotBlank()
                ) { Text("拉取") }
            },
            dismissButton = {
                TextButton(onClick = { showSubscription = false }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除节点") },
            text = { Text("确定删除「${node.name}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(node.id)
                        pendingDelete = null
                    }
                ) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun WarpCard(busy: Boolean, onFetch: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF10251F), Color(0xFF0E1B2A))
                ),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Mint.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Mint.copy(alpha = 0.12f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = Mint, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cloudflare WARP", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        "  免费",
                        style = MaterialTheme.typography.labelLarge,
                        color = Mint,
                        modifier = Modifier
                            .background(Mint.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    "官方免费 · 一键生成多出口线路",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Button(
                onClick = onFetch,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Mint,
                    contentColor = Color(0xFF03211A)
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF03211A)
                    )
                } else {
                    Text("获取", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    node: Node,
    selected: Boolean,
    health: NodeHealth,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) SurfaceHi.copy(alpha = 0.9f) else SurfaceColor.copy(alpha = 0.6f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) Mint.copy(alpha = 0.35f) else Stroke
        )
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .border(1.dp, if (selected) Mint else TextSecondary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(Modifier.size(10.dp).background(Mint, CircleShape))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    node.endpoint.ifBlank { "WireGuard 节点" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = TextSecondary.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HealthBadge(health)
            SourceChip(node.source)
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除节点",
                    tint = TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun HealthBadge(health: NodeHealth) {
    if (health == NodeHealth.UNKNOWN) return
    val (label, color) = when (health) {
        NodeHealth.CHECKING -> "检测中" to Sky
        NodeHealth.OK -> "可用" to Mint
        NodeHealth.FAIL -> "不可用" to Danger
        NodeHealth.UNKNOWN -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .padding(end = 5.dp)
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun SourceChip(source: NodeSource) {
    val (label, color) = when (source) {
        NodeSource.WARP -> "免费" to Mint
        NodeSource.SUBSCRIPTION -> "订阅" to Sky
        NodeSource.IMPORT -> "导入" to TextSecondary
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun EmptyNodes(modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(Mint.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = Mint.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
        }
        Text(
            "还没有节点",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            "点击上方「获取」生成多条 WARP 免费线路，或导入自己的配置",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
