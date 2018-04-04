package games.pong;

import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Deals with the player's paddle and score
 * Based on existing code found at:
 * https://github.com/catalinc/pong-game-android
 */

class Player {

    int paddleWidth;
    int paddleHeight;
    Paint paint;
    int score;
    RectF bounds;
    int collision;

    Player(int paddleWidth, int paddleHeight, Paint paint) {
        this.paddleWidth = paddleWidth;
        this.paddleHeight = paddleHeight;
        this.paint = paint;
        this.score = 0;
        this.bounds = new RectF(0, 0, paddleWidth, paddleHeight);
        this.collision = 0;
    }

}
