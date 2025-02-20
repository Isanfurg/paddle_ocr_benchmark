package com.example.test_ocr_sbw.utils;

import android.content.ContentResolver;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

import com.example.test_ocr_sbw.ocr.OcrResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class CsvUtils {

    /**
     * Escribe (en modo append) una línea CSV con formato:
     *   fileName,elapsedTime,confidenceAverage,recognizedTextAlfanumerico
     */
    public static void appendOcrResult(ContentResolver resolver, DocumentFile folder, OcrResult result) throws IOException {
        // Nombre del CSV
        String fileName = "resultados_ocr.csv";

        // Buscamos si ya existe
        DocumentFile csvFile = folder.findFile(fileName);
        if (csvFile == null) {
            // Si no existe, crearlo
            csvFile = folder.createFile("text/csv", fileName);
        }

        // Abrimos en modo "wa" (write-append)
        try (OutputStream out = resolver.openOutputStream(csvFile.getUri(), "wa");
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out))) {

            // Filtramos texto para sólo alfanumérico + espacios
            String alfanumerico = result.getAlphanumericText();

            // Construimos la línea CSV
            // Ejemplo: "foto.jpg,200,0.95,TextoReconocido"
            String line = result.getFileName() + "," +
                    result.getExecutionTimeMillis() + "," +
                    result.getConfidenceAverage() + "," +
                    alfanumerico + "\n";

            writer.write(line);
            writer.flush();
        }
    }
}
