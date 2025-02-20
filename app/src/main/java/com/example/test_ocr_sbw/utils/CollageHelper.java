package com.example.test_ocr_sbw.utils;



import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.List;

public class CollageHelper {

    /**
     * Crea un collage en forma de cuadrícula de 9x9 celdas en un Bitmap con dimensiones especificadas.
     * Se colocan las imágenes de la lista en orden (izquierda a derecha, de arriba a abajo).
     * Si hay menos de 81 imágenes, las celdas restantes se dejarán con fondo negro.
     *
     * @param images Lista de imágenes a colocar en el collage.
     * @param collageWidth Ancho del collage resultante.
     * @param collageHeight Alto del collage resultante.
     * @return Un Bitmap con el collage.
     */
    public static Bitmap createGridCollage(List<Bitmap> images, int collageWidth, int collageHeight) {
        int cols = 1;
        int rows = 2;
        int cellWidth = collageWidth / cols;
        int cellHeight = collageHeight / rows;

        Bitmap collage = Bitmap.createBitmap(collageWidth, collageHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(collage);
        // Rellenar el fondo con negro
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setFilterBitmap(true);

        int numCells = cols * rows; // 81 celdas
        for (int i = 0; i < images.size() && i < numCells; i++) {
            int row = i / cols;
            int col = i % cols;
            // Escalar la imagen para que se ajuste a la celda
            Bitmap scaledImage = Bitmap.createScaledBitmap(images.get(i), cellWidth, cellHeight, true);
            int x = col * cellWidth;
            int y = row * cellHeight;
            canvas.drawBitmap(scaledImage, x, y, paint);
        }
        return collage;
    }
}
