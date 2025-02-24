package com.example.test_ocr_sbw.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class CsvUtils {

    /**
     * Guarda el contenido CSV en un archivo.
     *
     * @param context Contexto de la aplicación.
     * @param csvData Cadena con datos CSV.
     * @param fileName Nombre del archivo (por ejemplo, "ocr_results.csv").
     * @return La ruta absoluta del archivo guardado o null si ocurrió un error.
     */
    public static String saveCsv(Context context, String csvData, String fileName) {
        File csvFile = new File(context.getExternalFilesDir(null), fileName);
        try (FileOutputStream fos = new FileOutputStream(csvFile)) {
            fos.write(csvData.getBytes());
            return csvFile.getAbsolutePath();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
