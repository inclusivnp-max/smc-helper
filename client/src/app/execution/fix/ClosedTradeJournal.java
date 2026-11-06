package app.execution.fix;

import app.common.ProjectPaths;
import app.model.AnalysisResult;
import app.model.TradePlan;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClosedTradeJournal {
    private static final String[] HEADERS = {
            "День", "Время", "Монета", "Позиция", "Цена входа", "Кол-во",
            "Тейк-профит", "TP2", "TP3", "Стоп-лосс", "Цена закрытия",
            "PNL ($)", "PNL %", "Результат", "Backtest Score", "Заметки",
            "Промежуток", "Таймфрейм", "Режим", "Биржа", "Setup",
            "Session", "Entry Confirmed", "Закрыто ISO", "ML Label"
    };
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] MONTHS = {
            "янв", "фев", "мар", "апр", "май", "июн",
            "июл", "авг", "сен", "окт", "ноя", "дек"
    };

    private final Path directory;
    private final Path csvPath;
    private final Path xlsxPath;

    public ClosedTradeJournal(Path directory) {
        this.directory = directory;
        this.csvPath = directory.resolve("closed_trades.csv");
        this.xlsxPath = directory.resolve("closed_trades.xlsx");
    }

    public static ClosedTradeJournal defaultJournal() {
        return new ClosedTradeJournal(ProjectPaths.closedTradesDir());
    }

    public Path csvPath() {
        return csvPath;
    }

    public Path xlsxPath() {
        return xlsxPath;
    }

    public synchronized void record(Record record) throws IOException {
        if (record == null) return;
        Files.createDirectories(directory);
        boolean writeHeader = !Files.exists(csvPath) || Files.size(csvPath) == 0L;
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (writeHeader) {
                writer.write(csv(HEADERS));
                writer.newLine();
            }
            writer.write(csv(record.columns()));
            writer.newLine();
        }
        rebuildXlsx(readCsvRows());
    }

    private List<String[]> readCsvRows() throws IOException {
        List<String[]> rows = new ArrayList<>();
        if (!Files.exists(csvPath)) return rows;
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) rows.add(parseCsv(line));
            }
        }
        return rows;
    }

    private void rebuildXlsx(List<String[]> rows) throws IOException {
        Path temp = xlsxPath.resolveSibling(xlsxPath.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", rels());
            entry(zip, "docProps/core.xml", coreProps());
            entry(zip, "docProps/app.xml", appProps());
            entry(zip, "xl/workbook.xml", workbookXml());
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            entry(zip, "xl/styles.xml", stylesXml());
            entry(zip, "xl/worksheets/sheet1.xml", sheetXml(rows));
        }
        try {
            Files.move(temp, xlsxPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, xlsxPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheetXml(List<String[]> rows) {
        int rowCount = Math.max(1, rows.size());
        StringBuilder sb = new StringBuilder(16_384);
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                           xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                <cols>
                """);
        int[] widths = {11, 10, 12, 11, 13, 11, 13, 11, 11, 12, 14, 11, 10, 13, 14, 28, 13, 12, 13, 18, 22, 18, 16, 20, 10};
        for (int i = 0; i < widths.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            int excelRow = r + 1;
            sb.append("<row r=\"").append(excelRow).append("\">");
            for (int c = 0; c < HEADERS.length; c++) {
                String value = c < row.length ? row[c] : "";
                int style = styleFor(r, c, value, row);
                appendCell(sb, excelRow, c + 1, value, style, numericColumn(c));
            }
            sb.append("</row>");
        }
        sb.append("</sheetData><autoFilter ref=\"A1:Y").append(rowCount).append("\"/>");
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static int styleFor(int rowIndex, int colIndex, String value, String[] row) {
        if (rowIndex == 0) return 1;
        String side = row.length > 3 ? row[3] : "";
        if (colIndex == 3) return "Short".equalsIgnoreCase(side) ? 3 : 2;
        if (colIndex == 11 || colIndex == 12 || colIndex == 13) {
            String pnl = row.length > 11 ? row[11] : value;
            if (pnl.trim().startsWith("-")) return 5;
            if (!pnl.isBlank() && !"0".equals(pnl.trim())) return 4;
        }
        if (numericColumn(colIndex)) return 6;
        return 0;
    }

    private static boolean numericColumn(int col) {
        return switch (col) {
            case 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 24 -> true;
            default -> false;
        };
    }

    private static void appendCell(StringBuilder sb, int row, int col, String value, int style, boolean numeric) {
        String ref = cellRef(row, col);
        String raw = value == null ? "" : value;
        if (numeric && isNumber(raw)) {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>")
                    .append(xmlNumber(raw)).append("</v></c>");
        } else {
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
                    .append("\" t=\"inlineStr\"><is><t>")
                    .append(xml(raw)).append("</t></is></c>");
        }
    }

    private static String cellRef(int row, int col) {
        StringBuilder letters = new StringBuilder();
        int c = col;
        while (c > 0) {
            int rem = (c - 1) % 26;
            letters.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return letters.append(row).toString();
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text.replace("%", ""));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String xmlNumber(String text) {
        return text == null ? "0" : text.replace("%", "").replace("+", "").trim();
    }

    private static String stylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="3">
                  <font><sz val="11"/><color rgb="FF000000"/><name val="Calibri"/></font>
                  <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
                  <font><b/><sz val="11"/><color rgb="FF006100"/><name val="Calibri"/></font>
                </fonts>
                <fills count="6">
                  <fill><patternFill patternType="none"/></fill>
                  <fill><patternFill patternType="gray125"/></fill>
                  <fill><patternFill patternType="solid"><fgColor rgb="FF20384F"/><bgColor indexed="64"/></patternFill></fill>
                  <fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/><bgColor indexed="64"/></patternFill></fill>
                  <fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/><bgColor indexed="64"/></patternFill></fill>
                  <fill><patternFill patternType="solid"><fgColor rgb="FFE2F0D9"/><bgColor indexed="64"/></patternFill></fill>
                </fills>
                <borders count="2">
                  <border><left/><right/><top/><bottom/><diagonal/></border>
                  <border><left style="thin"><color rgb="FF9E9E9E"/></left><right style="thin"><color rgb="FF9E9E9E"/></right><top style="thin"><color rgb="FF9E9E9E"/></top><bottom style="thin"><color rgb="FF9E9E9E"/></bottom><diagonal/></border>
                </borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="7">
                  <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
                  <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFill="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                  <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFill="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                  <xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                  <xf numFmtId="0" fontId="2" fillId="5" borderId="1" xfId="0" applyFill="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                  <xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                  <xf numFmtId="4" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
                </cellXfs>
                <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                </styleSheet>
                """;
    }

    private static String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>
                """;
    }

    private static String rels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>
                """;
    }

    private static String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="Closed Trades" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """;
    }

    private static String workbookRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    private static String coreProps() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                + "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:creator>SMC Chart</dc:creator><cp:lastModifiedBy>SMC Chart</cp:lastModifiedBy>"
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }

    private static String appProps() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
                            xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
                  <Application>SMC Chart</Application>
                </Properties>
                """;
    }

    private static String csv(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String value = values[i] == null ? "" : values[i];
            boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
            if (quote) sb.append('"').append(value.replace("\"", "\"\"")).append('"');
            else sb.append(value);
        }
        return sb.toString();
    }

    private static String[] parseCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cell.append(ch);
                }
            } else if (ch == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '"') {
                quoted = true;
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(String[]::new);
    }

    private static String xml(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public record Record(String day,
                         String time,
                         String symbol,
                         String side,
                         double entryPrice,
                         double quantity,
                         double tp1,
                         double tp2,
                         double tp3,
                         double stopLoss,
                         double closePrice,
                         double pnlUsd,
                         double pnlPct,
                         String result,
                         int score,
                         String notes,
                         String holdingInterval,
                         String timeframe,
                         String mode,
                         String venue,
                         String setup,
                         String session,
                         boolean entryConfirmed,
                         String closedAtIso,
                         int mlLabel) {
        String[] columns() {
            return new String[]{
                    day, time, symbol, side,
                    num(entryPrice), num(quantity), num(tp1), num(tp2), num(tp3), num(stopLoss), num(closePrice),
                    signed(pnlUsd), signed(pnlPct),
                    result, Integer.toString(score), notes, holdingInterval, timeframe,
                    mode, venue, setup, session, Boolean.toString(entryConfirmed), closedAtIso, Integer.toString(mlLabel)
            };
        }

        public static Record from(AnalysisResult analysis,
                                  OpenPositionSnapshot position,
                                  ExchangeAccountSettings settings,
                                  double closePrice,
                                  double closedQuantity,
                                  String venue,
                                  String reason) {
            LocalDateTime now = LocalDateTime.now();
            TradePlan plan = analysis == null ? null : analysis.plan;
            boolean longSide = position == null || position.longSide();
            double entry = position != null && position.entryPrice > 0.0
                    ? position.entryPrice
                    : plan == null ? 0.0 : plan.entry;
            double qty = closedQuantity > 0.0
                    ? closedQuantity
                    : position == null ? 0.0 : position.quantity;
            double pnl = entry > 0.0 && closePrice > 0.0
                    ? (longSide ? closePrice - entry : entry - closePrice) * qty
                    : 0.0;
            double pnlPct = entry > 0.0 && closePrice > 0.0
                    ? (longSide ? closePrice - entry : entry - closePrice) / entry * 100.0
                    : 0.0;
            String result = pnl > 0.00000001 ? "Зашла" : pnl < -0.00000001 ? "Убыток" : "Ноль";
            int score = analysis == null ? plan == null ? 0 : plan.confidence : analysis.smcScore;
            String session = analysis == null || analysis.sessionContext == null
                    ? ""
                    : analysis.sessionContext.activeSession + " / " + analysis.sessionContext.phase;
            String notes = buildNotes(analysis, reason);
            return new Record(
                    dayLabel(now),
                    now.format(TIME_FMT),
                    position == null ? analysis == null ? "" : safe(analysis.symbol) : safe(position.symbol),
                    longSide ? "Long" : "Short",
                    entry,
                    qty,
                    plan == null ? 0.0 : plan.tp1,
                    plan == null ? 0.0 : plan.tp2,
                    plan == null ? 0.0 : plan.tp3,
                    plan == null ? 0.0 : plan.stop,
                    closePrice,
                    pnl,
                    pnlPct,
                    result,
                    score,
                    notes,
                    "manual",
                    analysis == null ? "" : safe(analysis.ltfInterval),
                    settings == null ? "" : settings.mode.label,
                    venue == null || venue.isBlank()
                            ? settings == null ? "" : settings.exchange.label
                            : venue,
                    plan == null ? "" : safe(plan.setupType),
                    session,
                    plan != null && plan.entryConfirmed,
                    now.format(ISO_FMT),
                    pnl > 0.00000001 ? 1 : pnl < -0.00000001 ? -1 : 0);
        }
    }

    private static String buildNotes(AnalysisResult analysis, String reason) {
        List<String> parts = new ArrayList<>();
        if (reason != null && !reason.isBlank()) parts.add(reason);
        if (analysis != null && analysis.scoreFactors != null && !analysis.scoreFactors.isEmpty()) {
            parts.add(String.join("; ", analysis.scoreFactors.subList(0, Math.min(2, analysis.scoreFactors.size()))));
        }
        if (analysis != null && analysis.warnings != null && !analysis.warnings.isEmpty()) {
            parts.add("warnings: " + String.join("; ", analysis.warnings.subList(0, Math.min(2, analysis.warnings.size()))));
        }
        return String.join(" | ", parts);
    }

    private static String dayLabel(LocalDateTime time) {
        int idx = Math.max(0, Math.min(11, time.getMonthValue() - 1));
        return time.getDayOfMonth() + "." + MONTHS[idx];
    }

    private static String num(double value) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.0000000001) return "0";
        return String.format(Locale.US, "%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String signed(double value) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.0000000001) return "0";
        String text = String.format(Locale.US, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return value > 0.0 ? "+" + text : text;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
