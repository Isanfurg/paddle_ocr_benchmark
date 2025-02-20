package com.example.test_ocr_sbw.yolo;


import org.tensorflow.lite.support.metadata.MetadataExtractor;
import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetaData {

    // Evitamos la instanciación
    private MetaData() {
        throw new UnsupportedOperationException("No se puede instanciar MetaData");
    }

    /**
     * Extrae los nombres de la metadata del modelo.
     *
     * @param model MappedByteBuffer del modelo TFLite.
     * @return Lista de nombres extraídos o una lista vacía si ocurre algún error.
     */
    public static List<String> extractNamesFromMetadata(MappedByteBuffer model) {
        try {
            MetadataExtractor metadataExtractor = new MetadataExtractor(model);
            InputStream inputStream = metadataExtractor.getAssociatedFile("temp_meta.txt");
            if (inputStream == null) {
                return Collections.emptyList();
            }

            // Lee todo el contenido del InputStream
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder metadataBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                metadataBuilder.append(line).append("\n");
            }
            reader.close();
            String metadata = metadataBuilder.toString();

            // Utiliza expresión regular para extraer la parte con los nombres
            Pattern pattern = Pattern.compile("'names': \\{(.*?)\\}", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(metadata);
            if (!matcher.find()) {
                return Collections.emptyList();
            }
            String namesContent = matcher.group(1);
            if (namesContent == null) {
                return Collections.emptyList();
            }

            // Expresión regular para extraer los nombres (se admiten cadenas entre comillas dobles o simples)
            Pattern pattern2 = Pattern.compile("\"([^\"]*)\"|'([^']*)'");
            Matcher matcher2 = pattern2.matcher(namesContent);
            List<String> list = new ArrayList<>();
            while (matcher2.find()) {
                // Si el grupo 1 está vacío se usa el grupo 2
                String value = matcher2.group(1);
                if (value == null || value.isEmpty()) {
                    value = matcher2.group(2);
                }
                list.add(value);
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Extrae los nombres desde un archivo de labels ubicado en los assets.
     *
     * @param context   Contexto de la aplicación.
     * @param labelPath Ruta al archivo de labels (por ejemplo, "labels.txt").
     * @return Lista de labels leídos o una lista vacía en caso de error.
     */
    public static List<String> extractNamesFromLabelFile(Context context, String labelPath) {
        List<String> labels = new ArrayList<>();
        BufferedReader reader = null;
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open(labelPath);
            reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                labels.add(line);
            }
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    // Se ignora
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Se ignora
                }
            }
        }
        return labels;
    }

    /**
     * Lista de clases temporales (por ejemplo, en caso de que el modelo no contenga metadata).
     * Se generan 1000 nombres: "class1", "class2", ..., "class1000".
     */
    public static final List<String> TEMP_CLASSES;
    static {
        List<String> tempClasses = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            tempClasses.add("class" + (i + 1));
        }
        TEMP_CLASSES = Collections.unmodifiableList(tempClasses);
    }
}
