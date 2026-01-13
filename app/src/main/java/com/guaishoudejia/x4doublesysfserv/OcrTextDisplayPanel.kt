package com.guaishoudejia.x4doublesysfserv

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guaishoudejia.x4doublesysfserv.ocr.OcrHelper

/**
 * Gecko 前台服务 + OCR 文本显示集成组件
 * 用于启动离屏渲染、进行 OCR、显示识别文本
 */
data class OcrTextPageData(
    val blocks: List<String> = emptyList(),
    val pageIndex: Int = 0,
    val totalPages: Int = 0,
    val timestamp: Long = 0
)

class OcrTextDisplay {
    private val pages = mutableListOf<OcrTextPageData>()
    
    fun addPage(blocks: List<String>) {
        pages.add(
            OcrTextPageData(
                blocks = blocks,
                pageIndex = pages.size,
                totalPages = pages.size + 1,
                timestamp = System.currentTimeMillis()
            )
        )
    }
    
    fun getPage(index: Int): OcrTextPageData? {
        return if (index in pages.indices) pages[index] else null
    }
    
    fun getTotalPages(): Int = pages.size
    
    fun clear() {
        pages.clear()
    }
}

/**
 * OCR 文本显示屏组件
 */
@Composable
fun OcrTextDisplayScreen(
    currentPageIndex: Int = 0,
    totalPages: Int = 0,
    blocks: List<String> = emptyList(),
    isLoading: Boolean = false,
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OCR 识别文本",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${currentPageIndex + 1}/$totalPages",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 主要文本显示区域
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFF9F9F9)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("正在处理图像...", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            } else if (blocks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFF9F9F9)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无识别结果", fontSize = 14.sp, color = Color.Gray)
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFFAFAFA))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        blocks.forEachIndexed { index, text ->
                            Text(
                                text = text,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                            
                            if (index < blocks.size - 1) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Color.LightGray.copy(alpha = 0.3f))
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 底部控制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPreviousPage,
                    enabled = !isLoading,  // 只在处理中时禁用
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6200EE),
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("上一页", fontSize = 12.sp)
                }

                Button(
                    onClick = onNextPage,
                    enabled = !isLoading,  // 只在处理中时禁用
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6200EE),
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("下一页", fontSize = 12.sp)
                }

                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCC0000)
                    )
                ) {
                    Text("关闭", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * 启动 Gecko 前台服务的按钮组件
 */
@Composable
fun StartGeckoServiceButton(
    onClick: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier
            .height(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4CAF50),
            disabledContainerColor = Color.Gray
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = if (isLoading) "启动中..." else "启动 OCR 服务",
            fontSize = 12.sp,
            color = Color.White
        )
    }
}

/**
 * 集成面板：包含启动服务按钮和 OCR 文本显示
 */
@Composable
fun GeckoOcrIntegrationPanel(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit = {},
    isServiceRunning: Boolean = false,
    isOcrProcessing: Boolean = false,
    ocrBlocks: List<String> = emptyList(),
    currentPage: Int = 0,
    totalPages: Int = 0,
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onCloseOcr: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp)
    ) {
        // 启动服务按钮
        StartGeckoServiceButton(
            onClick = onStartService,
            isLoading = isOcrProcessing,
            modifier = Modifier.fillMaxWidth()
        )

        if (isServiceRunning && ocrBlocks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            
            // 文本预览
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 200.dp)
                    .background(Color(0xFFF9F9F9))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ocrBlocks.take(5).forEachIndexed { index, text ->
                        Text(
                            text = text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        if (index < 4) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(Color.LightGray.copy(alpha = 0.3f))
                            )
                        }
                    }
                    
                    if (ocrBlocks.size > 5) {
                        Text(
                            text = "... 还有 ${ocrBlocks.size - 5} 段",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 翻页按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPreviousPage,
                    enabled = currentPage > 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("上一页", fontSize = 10.sp)
                }

                Button(
                    onClick = onNextPage,
                    enabled = currentPage < totalPages - 1,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3),
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    Text("下一页", fontSize = 10.sp)
                }

                Button(
                    onClick = onCloseOcr,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF5722)
                    )
                ) {
                    Text("关闭", fontSize = 10.sp, color = Color.White)
                }
            }

            Text(
                text = "页数: ${currentPage + 1}/$totalPages",
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
