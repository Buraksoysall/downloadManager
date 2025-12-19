package com.example.videodownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.videodownloader.util.PreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefsManager = remember { PreferencesManager(context) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("Ayarlar") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                }
            }
        )
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Yasal Bilgiler Bölümü
            SettingsSection(
                title = "Yasal Bilgiler",
                icon = Icons.Default.Gavel
            ) {
                SettingsItem(
                    title = "Kullanım Şartları",
                    subtitle = "Uygulama kullanım koşulları ve sorumluluklar",
                    icon = Icons.Default.Description,
                    onClick = {
                        // Terms dialog'unu tekrar göster
                        prefsManager.resetTermsAcceptance()
                        // Activity'yi yeniden başlat
                        (context as? android.app.Activity)?.recreate()
                    }
                )
                
                SettingsItem(
                    title = "Gizlilik Politikası",
                    subtitle = "Kişisel verilerin korunması ve kullanımı",
                    icon = Icons.Default.PrivacyTip,
                    onClick = {
                        // Gizlilik politikası linkini aç
                        uriHandler.openUri("https://example.com/privacy-policy")
                    }
                )
                
                SettingsItem(
                    title = "DMCA ve Telif Hakları",
                    subtitle = "Telif ihlali bildirimi ve kaldırma prosedürü",
                    icon = Icons.Default.Copyright,
                    onClick = {
                        // DMCA politikası linkini aç
                        uriHandler.openUri("https://example.com/dmca-policy")
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // İletişim Bölümü
            SettingsSection(
                title = "İletişim",
                icon = Icons.Default.ContactMail
            ) {
                SettingsItem(
                    title = "DMCA Bildirimi",
                    subtitle = "dmca@example.com",
                    icon = Icons.Default.Email,
                    onClick = {
                        // Email uygulamasını aç
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:dmca@example.com")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "DMCA Takedown Request")
                        }
                        context.startActivity(intent)
                    }
                )
                
                SettingsItem(
                    title = "Destek",
                    subtitle = "support@example.com",
                    icon = Icons.Default.Support,
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:support@example.com")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Video Downloader Support")
                        }
                        context.startActivity(intent)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Uygulama Bilgileri
            SettingsSection(
                title = "Uygulama",
                icon = Icons.Default.Info
            ) {
                SettingsItem(
                    title = "Sürüm",
                    subtitle = "1.0.0",
                    icon = Icons.Default.AppSettingsAlt,
                    onClick = { }
                )
                
                SettingsItem(
                    title = "Açık Kaynak Lisansları",
                    subtitle = "Kullanılan kütüphaneler ve lisansları",
                    icon = Icons.Default.Code,
                    onClick = {
                        uriHandler.openUri("https://example.com/open-source-licenses")
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Uyarı Metni
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Önemli Uyarı",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bu uygulama yalnızca kullanım hakkınız bulunan içerikler için kullanılmalıdır. Telif hakkı ihlali yapmak yasaktır.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
