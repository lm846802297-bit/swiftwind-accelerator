package com.swiftwind.accelerator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swiftwind.accelerator.ui.theme.Pink
import com.swiftwind.accelerator.ui.theme.Rose
import com.swiftwind.accelerator.ui.theme.Stroke
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.ui.theme.TextSecondary

@Composable
fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF20B0710))
            .border(1.dp, Stroke.copy(alpha = 0.55f))
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BottomItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Home,
            label = "首页",
            selected = current == 0,
            onClick = { onSelect(0) }
        )
        BottomItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Public,
            label = "节点",
            selected = current == 1,
            onClick = { onSelect(1) }
        )
        BottomItem(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Settings,
            label = "设置",
            selected = current == 2,
            onClick = { onSelect(2) }
        )
    }
}

@Composable
private fun BottomItem(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (selected) Pink else TextSecondary
    Column(
        modifier
            .height(52.dp)
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(Pink.copy(alpha = 0.18f), Rose.copy(alpha = 0.08f)))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                },
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF26152F), Color(0xFF150B1E))),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Pink.copy(alpha = 0.20f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        content()
    }
}
