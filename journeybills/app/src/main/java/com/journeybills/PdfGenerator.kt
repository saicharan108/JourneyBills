package com.journeybills

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.journeybills.data.ExpenseEntity
import com.journeybills.data.FriendBalanceEntity
import com.journeybills.data.TripEntity
import com.journeybills.domain.Settlement
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {
    fun generateTripReport(
        context: Context,
        trip: TripEntity,
        friends: List<FriendBalanceEntity>,
        expenses: List<ExpenseEntity>,
        settlements: List<Settlement>,
        tags: List<com.journeybills.data.TagEntity>,
        resolvedMe: String
    ): File? {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paint = Paint()
        val paintTitle = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 28f
            color = Color.BLACK
        }
        val paintSubtitle = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 14f
            color = Color.DKGRAY
        }
        val paintSection = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 18f
            color = Color.rgb(63, 81, 181)
        }
        val paintNormal = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 12f
            color = Color.BLACK
        }
        val paintTableHdr = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 12f
            color = Color.WHITE
        }

        fun formatCurrencyAmount(amount: Double, currencyCode: String): String {
            val locale = when (currencyCode) {
                "INR" -> Locale("en", "IN")
                "EUR" -> Locale.FRANCE
                "GBP" -> Locale.UK
                else -> Locale.US
            }
            val formatter = java.text.NumberFormat.getNumberInstance(locale)
            formatter.minimumFractionDigits = 2
            formatter.maximumFractionDigits = 2
            return formatter.format(amount)
        }

        var yPos = 60f

        // Title
        canvas.drawText("Trip Report: ${trip.name}", 50f, yPos, paintTitle)
        yPos += 30f
        
        // Overview text
        var totalSpent = 0.0
        val tripExpenses = expenses.filter { 
            !it.description.startsWith("Payment:") && !it.description.startsWith("Debt Transfer:") 
        }
        for (ex in tripExpenses) { totalSpent += ex.amount }
        
        canvas.drawText("Total Spent: ${getCurrencySymbol(trip.currency)} ${formatCurrencyAmount(totalSpent, trip.currency)}", 50f, yPos, paintSubtitle)
        yPos += 20f
        canvas.drawText("Total Transactions: ${expenses.size}", 50f, yPos, paintSubtitle)
        yPos += 20f
        canvas.drawText("Total Expenses: ${tripExpenses.size}", 50f, yPos, paintSubtitle)
        yPos += 40f

        // 1. Settlements
        canvas.drawText("Settlements & Balances", 50f, yPos, paintSection)
        yPos += 20f
        
        if (settlements.isEmpty()) {
            canvas.drawText("All settled up!", 50f, yPos, paintNormal)
            yPos += 20f
        } else {
            settlements.forEach { settlement ->
                val fromName = if (settlement.fromId == null) resolvedMe else friends.find { it.id == settlement.fromId }?.name ?: "Unknown"
                val toName = if (settlement.toId == null) resolvedMe else friends.find { it.id == settlement.toId }?.name ?: "Unknown"
                canvas.drawText("$fromName owes $toName ${getCurrencySymbol(trip.currency)}${formatCurrencyAmount(settlement.amount.toDouble(), trip.currency)}", 50f, yPos, paintNormal)
                yPos += 20f
                if (yPos > pageHeight - 50) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    yPos = 50f
                }
            }
        }
        yPos += 30f

        // 2. Spending by Category (Plain Text + Percentages)
        if (yPos > pageHeight - 200) {
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            yPos = 50f
        }

        canvas.drawText("Spending by Category", 50f, yPos, paintSection)
        yPos += 20f
        
        val categoryTotals = mutableMapOf<String, Double>()
        for (ex in tripExpenses) {
            val tag = tags.find { it.id == ex.tagId }
            val catName = if (tag != null) "${tag.emoji} ${tag.name}" else "🏷️ General"
            categoryTotals[catName] = categoryTotals.getOrDefault(catName, 0.0) + ex.amount
        }
        
        val sortedCategories = categoryTotals.entries.sortedByDescending { it.value }
        
        for ((cat, amount) in sortedCategories) {
            if (yPos > pageHeight - 50) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPos = 50f
            }
            val percentage = if (totalSpent > 0) (amount / totalSpent) * 100 else 0.0
            canvas.drawText(cat.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } + ": ", 50f, yPos, paintNormal)
            canvas.drawText("${getCurrencySymbol(trip.currency)}${formatCurrencyAmount(amount, trip.currency)} (${String.format(Locale.US, "%.1f", percentage)}%)", 200f, yPos, paintNormal)
            yPos += 20f
        }
        yPos += 30f

        // 3. Category Pie Chart
        if (yPos > pageHeight - 250) {
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            yPos = 50f
        }
        
        canvas.drawText("Category Pie Chart (Top 75%)", 50f, yPos, paintSection)
        yPos += 30f
        
        val colorArray = listOf(
            Color.rgb(255, 152, 0), Color.rgb(76, 175, 80), Color.rgb(33, 150, 243), 
            Color.rgb(233, 30, 99), Color.rgb(156, 39, 176), Color.rgb(0, 188, 212)
        )
        
        var pieRunningTotal = 0.0
        val pieData = mutableListOf<Pair<String, Double>>()
        var otherAmount = 0.0
        val target75 = totalSpent * 0.75
        
        for ((cat, amount) in sortedCategories) {
            if (pieRunningTotal < target75) {
                pieData.add(Pair(cat, amount))
                pieRunningTotal += amount
            } else {
                otherAmount += amount
            }
        }
        if (otherAmount > 0) {
            pieData.add(Pair("📦 Other", otherAmount))
        }
        
        val pieRadius = 80f
        val pieCenterX = pageWidth / 2f
        val pieCenterY = yPos + pieRadius
        val rectF = RectF(pieCenterX - pieRadius, pieCenterY - pieRadius, pieCenterX + pieRadius, pieCenterY + pieRadius)
        
        var currentAngle = 0f
        var colorIdx = 0
        
        for ((cat, amount) in pieData) {
            val sweepAngle = if (totalSpent > 0) ((amount / totalSpent) * 360).toFloat() else 0f
            paint.color = colorArray[colorIdx % colorArray.size]
            canvas.drawArc(rectF, currentAngle, sweepAngle, true, paint)
            currentAngle += sweepAngle
            colorIdx++
        }
        
        yPos = pieCenterY + pieRadius + 30f
        
        // Output Pie Legend
        var legendX = 50f
        var legendY = yPos
        colorIdx = 0
        for ((cat, _) in pieData) {
            paint.color = colorArray[colorIdx % colorArray.size]
            canvas.drawRect(legendX, legendY - 10f, legendX + 10f, legendY, paint)
            
            val truncatedCat = if (cat.length > 15) cat.take(13) + ".." else cat
            canvas.drawText(truncatedCat, legendX + 15f, legendY, paintNormal)
            
            legendX += 130f
            if (legendX > pageWidth - 100f) {
                legendX = 50f
                legendY += 20f
            }
            colorIdx++
        }
        yPos = legendY + 40f

        // 4. Daily Spending Line Graph
        if (yPos > pageHeight - 250) {
            pdfDocument.finishPage(page)
            pageNumber++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = pdfDocument.startPage(pageInfo)
            canvas = page.canvas
            yPos = 50f
        }
        
        canvas.drawText("Daily Spending Over Time", 50f, yPos, paintSection)
        yPos += 30f
        
        val dailyTotals = mutableMapOf<String, Double>()
        val dayFormat = SimpleDateFormat("MMM dd", Locale.US)
        val daySortFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        
        for (ex in tripExpenses) {
            val sDate = daySortFormat.format(Date(ex.timestamp))
            dailyTotals[sDate] = dailyTotals.getOrDefault(sDate, 0.0) + ex.amount
        }
        
        val sortedDays = dailyTotals.keys.sorted()
        
        if (sortedDays.isNotEmpty()) {
            val maxDayValue = dailyTotals.values.maxOrNull() ?: 1.0
            val chartHeight = 100f
            val chartWidth = pageWidth - 100f
            val startX = 50f
            val startY = yPos + chartHeight
            
            paint.color = Color.LTGRAY
            paint.strokeWidth = 1f
            // Axes
            canvas.drawLine(startX, startY, startX + chartWidth, startY, paint) // X
            canvas.drawLine(startX, startY, startX, startY - chartHeight, paint) // Y
            
            val stepX = if (sortedDays.size > 1) chartWidth / (sortedDays.size - 1) else chartWidth
            var prevX = -1f
            var prevY = -1f
            
            paint.color = Color.rgb(63, 81, 181)
            paint.strokeWidth = 2f
            
            // X-axis labels: only show a few if there are many days
            val labelInterval = Math.max(1, sortedDays.size / 6)
            
            sortedDays.forEachIndexed { index, sDate ->
                val amount = dailyTotals[sDate] ?: 0.0
                val px = startX + (index * stepX)
                val py = startY - ((amount / maxDayValue) * chartHeight).toFloat()
                
                canvas.drawCircle(px, py, 3f, paint)
                
                if (prevX != -1f && prevY != -1f) {
                    canvas.drawLine(prevX, prevY, px, py, paint)
                }
                
                if (index % labelInterval == 0 || index == sortedDays.size - 1) {
                    val dateObj = daySortFormat.parse(sDate)
                    val label = if (dateObj != null) dayFormat.format(dateObj) else sDate
                    canvas.drawText(label, px - 15f, startY + 15f, paintNormal)
                }
                
                prevX = px
                prevY = py
            }
        } else {
            canvas.drawText("No spending data available.", 50f, yPos, paintNormal)
        }
        
        yPos += 150f

        // 5. Transactions Table (New Page)
        pdfDocument.finishPage(page)
        pageNumber++
        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        page = pdfDocument.startPage(pageInfo)
        canvas = page.canvas
        yPos = 50f

        canvas.drawText("All Transactions (For CSV/Excel Import)", 50f, yPos, paintSection)
        yPos += 20f

        val tableMargin = 40f
        val colWidths = listOf(80f, 180f, 80f, 90f, 85f)
        val headers = listOf("Date", "Description", "Category", "Paid By", "Amount")

        // Draw Table Header
        paint.color = Color.rgb(63, 81, 181)
        canvas.drawRect(tableMargin, yPos - 15f, pageWidth - tableMargin, yPos + 5f, paint)
        var xPos = tableMargin + 5f
        headers.forEachIndexed { index, header ->
            canvas.drawText(header, xPos, yPos, paintTableHdr)
            xPos += colWidths[index]
        }
        yPos += 20f
        
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        expenses.sortedByDescending { it.timestamp }.forEach { expense ->
            if (yPos > pageHeight - 40) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPos = 50f
                // Re-draw headers
                paint.color = Color.rgb(63, 81, 181)
                canvas.drawRect(tableMargin, yPos - 15f, pageWidth - tableMargin, yPos + 5f, paint)
                var xp = tableMargin + 5f
                headers.forEachIndexed { idx, header ->
                    canvas.drawText(header, xp, yPos, paintTableHdr)
                    xp += colWidths[idx]
                }
                yPos += 20f
            }
            
            xPos = tableMargin + 5f
            val dt = sdf.format(Date(expense.timestamp))
            val desc = if (expense.description.length > 25) expense.description.take(23) + ".." else expense.description
            val tag = tags.find { it.id == expense.tagId }
            val cat = if (tag != null) tag.name else "General"
            val pb = if (expense.paidById == null) resolvedMe else friends.find { it.id == expense.paidById }?.name ?: "Unknown"
            val amtText = "${getCurrencySymbol(trip.currency)}${formatCurrencyAmount(expense.amount, trip.currency)}"
            
            paint.color = Color.LTGRAY
            canvas.drawLine(tableMargin, yPos + 5f, pageWidth - tableMargin, yPos + 5f, paint)
            
            canvas.drawText(dt, xPos, yPos, paintNormal)
            xPos += colWidths[0]
            canvas.drawText(desc, xPos, yPos, paintNormal)
            xPos += colWidths[1]
            canvas.drawText(cat, xPos, yPos, paintNormal)
            xPos += colWidths[2]
            canvas.drawText(pb, xPos, yPos, paintNormal)
            xPos += colWidths[3]
            canvas.drawText(amtText, xPos, yPos, paintNormal)
            
            yPos += 20f
        }
        
        pdfDocument.finishPage(page)

        return try {
            val pdfsDir = File(context.cacheDir, "pdfs")
            if (!pdfsDir.exists()) pdfsDir.mkdirs()
            val file = File(pdfsDir, "TripReport_${trip.name.replace(" ", "_")}.pdf")
            pdfDocument.writeTo(FileOutputStream(file))
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            pdfDocument.close()
        }
    }
}
