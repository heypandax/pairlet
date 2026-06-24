package dev.ccpocket.app.desktop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.resolve

/** Not-connected state: pick a paired computer, or pair a new one with the 6-digit code it prints. */
@Composable
fun ConnectPanel(repo: PocketRepository) {
    Box(Modifier.fillMaxSize().background(Tok.base), contentAlignment = Alignment.Center) {
        Column(Modifier.width(380.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CC Pocket", color = Tok.tx, fontFamily = Dk.ui, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            if (repo.addingDevice.value || repo.pairedList.isEmpty()) PairingForm(repo) else DevicePicker(repo)
            Spacer(Modifier.height(16.dp))
            Text(repo.status.value.resolve(), color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PairingForm(repo: PocketRepository) {
    var code by remember { mutableStateOf("") }
    Text("Connect a computer", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 14.sp)
    Spacer(Modifier.height(14.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (code.isEmpty()) Text("6-digit code", color = Tok.muted, fontFamily = Dk.mono, fontSize = 18.sp)
        BasicTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6); if (code.length == 6) repo.pairWithCode(code) },
            singleLine = true,
            textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 18.sp, letterSpacing = 4.sp),
            cursorBrush = SolidColor(Tok.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(10.dp))
    PrimaryButton("Connect", enabled = code.length == 6) { repo.pairWithCode(code) }
    Spacer(Modifier.height(12.dp))
    Text("Run  cc-pocket pair  on the other computer to get a code.", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp, textAlign = TextAlign.Center)
    if (repo.pairedList.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("Back", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { repo.cancelAddDevice() }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun DevicePicker(repo: PocketRepository) {
    Text("Choose a computer", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 14.sp)
    Spacer(Modifier.height(14.dp))
    repo.pairedList.forEach { d ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)).clickable { repo.switchDaemon(d) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.LaptopMac, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
            Text(d.displayName(), color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { repo.beginAddDevice() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = Tok.accent, modifier = Modifier.size(16.dp))
        Text("Add computer", color = Tok.accent, fontFamily = Dk.ui, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Tok.base else Tok.muted, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Tok.accent else Tok.surface)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 11.dp),
    )
}
