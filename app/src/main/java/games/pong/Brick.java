package games.pong;

import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Brick.
 * Created by Isaiah on 3/25/2018.
 */

public class Brick {
    private RectF coords;
    Paint paint;
    int health;

    Brick(RectF coords, Paint paint) {
        this.coords = coords;
        this.paint = paint;
        this.health = 1;
    }

    public RectF getCoords() {
        return this.coords;
    }
}
