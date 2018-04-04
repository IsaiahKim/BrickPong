package games.pong;

import android.graphics.Paint;

/**
 * All properties of a given ball
 * Based on existing code found at:
 * https://github.com/catalinc/pong-game-android
 */

class Ball {

    float cx;
    float cy;
    float dx;
    float dy;
    int radius;
    Paint paint;

    Ball(int radius, Paint paint) {
        this.radius = radius;
        this.paint = paint;
    }

}
