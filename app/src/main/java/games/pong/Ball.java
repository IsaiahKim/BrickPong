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
    float radius;
    Paint paint;

    Ball(float radius, Paint paint) {
        this.radius = radius;
        this.paint = paint;
    }

}
