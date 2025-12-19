package com.example.videodownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TermsDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var acceptChecked by remember { mutableStateOf(false) }
    
    Dialog(
        onDismissRequest = { /* Kapatılamaz */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Başlık
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Kullanım Şartları",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // İçerik
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "⚠️ ÖNEMLİ UYARI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5722),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = """
Bu uygulama yalnızca aşağıdaki koşullarda kullanılabilir:

📋 YASAL KULLANIM KOŞULLARI:
• Sadece size ait olan veya kullanım hakkınız bulunan içerikleri indirin
• Telif hakkı koruması altındaki içerikleri indirmek yasaktır
• Ticari platformlardan (YouTube, Netflix, vb.) indirme yapılamaz
• DRM korumalı içerikler desteklenmez

🚫 YASAKLI PLATFORMLAR:
• YouTube, Netflix, Prime Video, Disney+
• Spotify, Apple Music, sosyal medya
• Türk platformları: Exxen, BluTV, PuhuTV
• Diğer telif korumalı siteler

⚖️ SORUMLULUK REDDİ:
• Kullanıcı tüm yasal sorumluluğu kabul eder
• Uygulama geliştiricisi telif ihlalinden sorumlu değildir
• DMCA şikayetleri için: dmca@example.com

🎯 UYGUN KULLANIM ÖRNEKLERİ:
• Kişisel web sitenizden içerik indirme
• Açık kaynak/Creative Commons içerikler
• Eğitim amaçlı kendi ürettiğiniz materyaller
• Kullanım izni aldığınız içerikler

Bu şartları kabul etmeden uygulamayı kullanamazsınız.
                        """.trimIndent(),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Onay checkbox'ı
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptChecked,
                        onCheckedChange = { acceptChecked = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Yukarıdaki şartları okudum, anladım ve kabul ediyorum",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Butonlar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF5722)
                        )
                    ) {
                        Text("Reddet")
                    }
                    
                    Button(
                        onClick = onAccept,
                        enabled = acceptChecked,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Text("Kabul Et")
                    }
                }
            }
        }
    }
}
