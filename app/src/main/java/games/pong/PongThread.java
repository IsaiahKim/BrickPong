package games.pong;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import java.util.ArrayList;

import java.util.Random;

/**
 * Handle animation, game logic and user input.
 * Based on existing code found at:
 * https://github.com/catalinc/pong-game-android
 */
public class PongThread extends Thread {

    public static final int STATE_PAUSE   = 0;
    public static final int STATE_READY   = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_END    = 3;

    private static final int    PHYS_BALL_SPEED       = 20; //TODO: Fix clipping at high speeds
    private static final int    PHYS_PADDLE_SPEED     = 40;
    private static final int    PHYS_FPS              = 60;
    private static final double PHYS_MAX_BOUNCE_ANGLE = 5 * Math.PI / 12; // 75 degrees in radians
    private static final int    PHYS_COLLISION_FRAMES = 5;

    private static final String KEY_HUMAN_PLAYER_DATA    = "humanPlayer";
    private static final String KEY_COMPUTER_PLAYER_DATA = "computerPlayer";
    private static final String KEY_BALL_DATA            = "ball";
    private static final String KEY_GAME_STATE           = "state";
    private static final String KEY_BALL_COUNT           = "count";
    private static final String KEY_BRICK_COUNT          = "bricks";
    private static final String KEY_BRICK_COORDINATES    = "coordinates";
    private static final String KEY_BRICK_COLOR          = "color";

    private static final String TAG = "PongThread";

    private static final float BRICK_HEIGHT = 100;
    private static final float BRICK_WIDTH = 100;

    private static final int TOP_BOTTOM_HIT = 1;
    private static final int LEFT_RIGHT_HIT = 2;
    private static final int TOP_LEFT_HIT = 3;
    private static final int BOTTOM_LEFT_HIT = 4;
    private static final int TOP_RIGHT_HIT = 5;
    private static final int BOTTOM_RIGHT_HIT = 6;

    private final SurfaceHolder mSurfaceHolder;

    private final Handler mStatusHandler;

    private final Handler mScoreHandler;

    private final Context mContext;

    private       boolean mRun;
    private final Object  mRunLock;

    private int mState;

    private Player mHumanPlayer;
    private Player mComputerPlayer;
    private ArrayList<Ball> mBalls;
    private ArrayList<Brick> mBricks;

    private Paint mMedianLinePaint;

    private Paint mCanvasBoundsPaint;
    private int   mBallRadius;
    private int   mCanvasHeight;
    private int   mCanvasWidth;

    /**
     * Used to make computer to "forget" to move the paddle like a human opponent.
     */
    private Random mRandomGen;

    /**
     * The probability to move computer paddle.
     */
    private float mComputerMoveProbability;


    PongThread(final SurfaceHolder surfaceHolder,
               final Context context,
               final Handler statusHandler,
               final Handler scoreHandler,
               final AttributeSet attributeSet) {
        mSurfaceHolder = surfaceHolder;
        mStatusHandler = statusHandler;
        mScoreHandler = scoreHandler;
        mContext = context;

        mRun = false;
        mRunLock = new Object();

        TypedArray a = context.obtainStyledAttributes(attributeSet, R.styleable.PongView);

        int paddleHeight = a.getInt(R.styleable.PongView_paddleHeight, 200);
        int paddleWidth = a.getInt(R.styleable.PongView_paddleWidth, 45);
        mBallRadius = a.getInt(R.styleable.PongView_ballRadius, 15);

        a.recycle();

        Paint humanPlayerPaint = new Paint();
        humanPlayerPaint.setAntiAlias(true);
        humanPlayerPaint.setColor(Color.BLUE);

        mHumanPlayer = new Player(paddleWidth, paddleHeight, humanPlayerPaint);

        Paint computerPlayerPaint = new Paint();
        computerPlayerPaint.setAntiAlias(true);
        computerPlayerPaint.setColor(Color.RED);

        mComputerPlayer = new Player(paddleWidth, paddleHeight, computerPlayerPaint);

        Paint ballPaint = new Paint();
        ballPaint.setAntiAlias(true);
        ballPaint.setColor(Color.GREEN);
        Ball mBall = new Ball(mBallRadius, ballPaint);

        mBalls = new ArrayList<>();
        mBalls.add(mBall);

        mMedianLinePaint = new Paint();
        mMedianLinePaint.setAntiAlias(true);
        mMedianLinePaint.setColor(Color.YELLOW);
        mMedianLinePaint.setAlpha(80);
        mMedianLinePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mMedianLinePaint.setStrokeWidth(2.0f);
        mMedianLinePaint.setPathEffect(new DashPathEffect(new float[]{5, 5}, 0));

        mCanvasBoundsPaint = new Paint();
        mCanvasBoundsPaint.setAntiAlias(true);
        mCanvasBoundsPaint.setColor(Color.YELLOW);
        mCanvasBoundsPaint.setStyle(Paint.Style.STROKE);
        mCanvasBoundsPaint.setStrokeWidth(1.0f);

        mCanvasHeight = 1;
        mCanvasWidth = 1;

        mRandomGen = new Random();
        mComputerMoveProbability = 0.6f;

        mBricks = new ArrayList<>();
    }

    /**
     * The game loop.
     */
    @Override
    public void run() {
        long mNextGameTick = SystemClock.uptimeMillis();
        int skipTicks = 1000 / PHYS_FPS;
        while (mRun) {
            Canvas c = null;
            try {
                c = mSurfaceHolder.lockCanvas(null);
                if (c != null) {
                    synchronized (mSurfaceHolder) {
                        if (mState == STATE_RUNNING) {
                            updatePhysics();
                        }
                        synchronized (mRunLock) {
                            if (mRun) {
                                updateDisplay(c);
                            }
                        }
                    }
                }
            } finally {
                if (c != null) {
                    mSurfaceHolder.unlockCanvasAndPost(c);
                }
            }
            mNextGameTick += skipTicks;
            long sleepTime = mNextGameTick - SystemClock.uptimeMillis();
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted", e);
                }
            }
        }
    }

    void setRunning(boolean running) {
        synchronized (mRunLock) {
            mRun = running;
        }
    }

    void saveState(Bundle map) {
        synchronized (mSurfaceHolder) {
            map.putFloatArray(KEY_HUMAN_PLAYER_DATA,
                    new float[]{mHumanPlayer.bounds.left, mHumanPlayer.bounds.top,
                            mHumanPlayer.score});

            map.putFloatArray(KEY_COMPUTER_PLAYER_DATA,
                    new float[]{mComputerPlayer.bounds.left, mComputerPlayer.bounds.top,
                            mComputerPlayer.score});

            int ballCount = mBalls.size();
            map.putInt(KEY_BALL_COUNT, ballCount);

            for (int i=0;i<ballCount;i++) {
                Ball target = mBalls.get(i);
                map.putFloatArray(KEY_BALL_DATA + Integer.toString(i),
                        new float[]{target.cx, target.cy, target.dx, target.dy});
            }

            int brickCount = mBricks.size();
            map.putInt(KEY_BRICK_COUNT, brickCount);

            for (int j=0;j<brickCount;j++) {
                Brick brick = mBricks.get(j);
                RectF coords = brick.getCoords();
                map.putFloatArray(KEY_BRICK_COORDINATES + Integer.toString(j),
                        new float[]{coords.left, coords.top, coords.right, coords.bottom});
                map.putInt(KEY_BRICK_COLOR + Integer.toString(j), brick.paint.getColor());
            }

            map.putInt(KEY_GAME_STATE, mState);
        }
    }

    void restoreState(Bundle map) { // TODO: Doesn't actually work
        synchronized (mSurfaceHolder) {
            float[] humanPlayerData = map.getFloatArray(KEY_HUMAN_PLAYER_DATA);
            mHumanPlayer.score = (int) humanPlayerData[2];
            movePlayer(mHumanPlayer, humanPlayerData[0], humanPlayerData[1]);

            float[] computerPlayerData = map.getFloatArray(KEY_COMPUTER_PLAYER_DATA);
            mComputerPlayer.score = (int) computerPlayerData[2];
            movePlayer(mComputerPlayer, computerPlayerData[0], computerPlayerData[1]);

            int ballCount = map.getInt(KEY_BALL_COUNT);
            float ballRadius = mBalls.get(0).radius;

            for (int i=0;i<ballCount;i++) {
                if (i >= mBalls.size()) {
                    Paint ballPaint = new Paint();
                    ballPaint.setAntiAlias(true);
                    ballPaint.setColor(Color.GREEN);

                    mBalls.add(new Ball(ballRadius, ballPaint));
                }
                Ball target = mBalls.get(i);
                float[] ballData = map.getFloatArray(KEY_BALL_DATA + Integer.toString(i));
                target.cx = ballData[0];
                target.cy = ballData[1];
                target.dx = ballData[2];
                target.dy = ballData[3];
            }

            int brickCount = map.getInt(KEY_BALL_COUNT);

            for (int j=0;j<brickCount;j++) {
                Paint brickPaint = new Paint();
                brickPaint.setAntiAlias(true);
                brickPaint.setColor(map.getInt(KEY_BRICK_COLOR));
                float[] rawCoords =
                        map.getFloatArray(KEY_BRICK_COORDINATES + Integer.toString(j));
                RectF coords = new RectF(rawCoords[0], rawCoords[1], rawCoords[2], rawCoords[3]);
                mBricks.add(new Brick(coords, brickPaint));
            }

            int state = map.getInt(KEY_GAME_STATE);
            setState(state);
        }
    }

    void setState(int mode) {
        synchronized (mSurfaceHolder) {
            mState = mode;
            Resources res = mContext.getResources();
            switch (mState) {
                case STATE_READY:
                    setupNewRound();
                    break;
                case STATE_RUNNING:
                    hideStatusText();
                    break;
                case STATE_END:
                    if (mHumanPlayer.score > mComputerPlayer.score) {
                        setStatusText(res.getString(R.string.mode_win));
                    }
                    else if (mHumanPlayer.score == mComputerPlayer.score) {
                        setStatusText(res.getString(R.string.mode_tie));
                    }
                    else {
                        setStatusText(res.getString(R.string.mode_lose));
                    }
                    mComputerPlayer.score = 0;
                    mHumanPlayer.score = 0;
                    setupNewRound();
                    break;
                case STATE_PAUSE:
                    setStatusText(res.getString(R.string.mode_pause));
                    break;
            }
        }
    }

    void pause() {
        synchronized (mSurfaceHolder) {
            if (mState == STATE_RUNNING) {
                setState(STATE_PAUSE);
            }
        }
    }

    void unPause() {
        synchronized (mSurfaceHolder) {
            setState(STATE_RUNNING);
        }
    }

    /**
     * Reset score and start new game.
     */
    void startNewGame() {
        synchronized (mSurfaceHolder) {
            mHumanPlayer.score = 0;
            mComputerPlayer.score = 0;
            setupNewRound();
            setState(STATE_RUNNING);
        }
    }

    /**
     * @return true if the game is in win, lose or pause state.
     */
    boolean isBetweenRounds() {
        return mState != STATE_RUNNING;
    }

    boolean isTouchOnHumanPaddle(MotionEvent event) {
        return mHumanPlayer.bounds.contains(event.getX(), event.getY());
    }

    void moveHumanPaddle(float dy) {
        synchronized (mSurfaceHolder) {
            movePlayer(mHumanPlayer, mHumanPlayer.bounds.left, mHumanPlayer.bounds.top + dy);
        }
    }

    void setSurfaceSize(int width, int height) {
        synchronized (mSurfaceHolder) {
            mCanvasWidth = width;
            mCanvasHeight = height;
            setupNewRound();
        }
    }

    /**
     * Update paddle and player positions, check for collisions, win or lose.
     */
    private void updatePhysics() {

        if (mHumanPlayer.collision > 0) {
            mHumanPlayer.collision--;
        }
        if (mComputerPlayer.collision > 0) {
            mComputerPlayer.collision--;
        }
        for (int i=0;i<mBalls.size();i++) {
            Ball ball = mBalls.get(i);

            if (collision(mHumanPlayer, ball)) {
                handleCollision(mHumanPlayer, ball);
                mHumanPlayer.collision = PHYS_COLLISION_FRAMES;
            } else if (collision(mComputerPlayer, ball)) {
                handleCollision(mComputerPlayer, ball);
                mComputerPlayer.collision = PHYS_COLLISION_FRAMES;
            } else if (ballCollidedWithTopOrBottomWall(ball)) {
                ball.dy = -ball.dy;
            } else if (ballCollidedWithRightWall(ball)) {
                mHumanPlayer.score++;   // human plays on left
                if (mBalls.size() > 1) {
                    mBalls.remove(ball);
                }
                else {
                    setState(STATE_END);
                    return;
                }
            } else if (ballCollidedWithLeftWall(ball)) {
                mComputerPlayer.score++;
                if (mBalls.size() > 1) {
                    mBalls.remove(ball);
                }
                else {
                    setState(STATE_END);
                    return;
                }
            }

            /* These flags prevent the ball from hitting a shared corner of two bricks and continue
            to go in the same direction as before.
             */
            boolean pass = false;
            boolean TLHit, TRHit, BLHit, BRHit;
            TLHit = TRHit = BLHit = BRHit = false;
            float cornerX, cornerY;
            cornerX = cornerY = 0;

            for (int j = 0; j<mBricks.size(); j++) {
                Brick brick = mBricks.get(j);
                int collisionStatus = collision(ball, brick);
                if (collisionStatus > 0) {
                    if (collisionStatus == TOP_BOTTOM_HIT) {
                        Log.d("Collision Resolution", "Top-Bottom Collision Resolved");
                        ball.dx = -ball.dx;
                        pass = true;
                        mBricks.remove(brick);
                        break;
                    } else if (collisionStatus == LEFT_RIGHT_HIT) {
                        Log.d("Collision Resolution", "Left-Right Collision Resolved");
                        ball.dy = -ball.dy;
                        pass = true;
                        mBricks.remove(brick);
                        break;
                    } else if (collisionStatus == TOP_LEFT_HIT) {
                        TLHit = true;
                        Log.d("Collision Count", "Top-Left Collision Counted");
                        if (!(TRHit || BLHit || BRHit)) {
                            cornerX = brick.getCoords().left;
                            cornerY = brick.getCoords().top;
                        }
                    } else if (collisionStatus == TOP_RIGHT_HIT) {
                        TRHit = true;
                        Log.d("Collision Count", "Top-Right Collision Counted");
                        if (!(TLHit || BLHit || BRHit)) {
                            cornerX = brick.getCoords().right;
                            cornerY = brick.getCoords().top;
                        }

                    } else if (collisionStatus == BOTTOM_LEFT_HIT) {
                        BLHit = true;
                        Log.d("Collision Count", "Bottom-Left Collision Counted");
                        if (!(TRHit || TLHit || BRHit)) {
                            cornerX = brick.getCoords().left;
                            cornerY = brick.getCoords().bottom;
                        }

                    } else if (collisionStatus == BOTTOM_RIGHT_HIT) {
                        BRHit = true;
                        Log.d("Collision Count", "Bottom-Right Collision Counted");
                        if (!(TRHit || BLHit || TLHit)) {
                            cornerX = brick.getCoords().right;
                            cornerY = brick.getCoords().bottom;
                        }

                    }

                    mBricks.remove(brick);
                }
            }
            if (!pass) { //TODO: REDO FOR NON-ALIGNED BRICKS
                int corners = cornerCount(TLHit, TRHit, BLHit, BRHit);
                if (corners >= 3) {
                    Log.d("Collision Resolution", "Three Corner Collision Resolved");
                    ball.dx *= -1;
                    ball.dy *= -1;
                }
                if (corners == 2) {
                    Log.d("Collision Resolution", "Two Corner Collision");
                    if ((TLHit && TRHit) || (BLHit && BRHit)) {
                        Log.d("Collision Resolution", "Y-Axis Collision");
                        ball.dy *= -1;
                    }
                    else if ((TLHit && BLHit) || (TRHit && BRHit)) {
                        Log.d("Collision Resolution", "X-Axis Collision");
                        ball.dx *= -1;
                    }
                    else {
                        Log.d("Collision Resolution", "Corner Bounce");
                        ball.dx *= -1;
                        ball.dy *= -1;
                    }
                }
                if (corners == 1) { //TODO: Ensure the ball doesn't bounce sideways for eternity
                    Log.d("Collision Resolution", "Single Corner Collision Resolved");
                    Log.d("Old Velocity", "dx: " + Float.toString(ball.dx) + " dy:" +
                            Float.toString(ball.dy) + " Speed: " +
                            Double.toString(Math.sqrt(ball.dx*ball.dx + ball.dy*ball.dy)));
                    double normX = ball.cx - cornerX;
                    double normY = ball.cy - cornerY;
                    double c = -2*(ball.dx*normX + ball.dy*normY)/
                            (normX*normX + normY*normY);
                    ball.dx = (float) (ball.dx + c*normX);
                    ball.dy = (float) (ball.dy + c*normY);
                    Log.d("New Velocity", "dx: " + Float.toString(ball.dx) + " dy:" +
                            Float.toString(ball.dy) + " Speed: " +
                            Double.toString(Math.sqrt(ball.dx*ball.dx + ball.dy*ball.dy)));
                }
            }

            if (mRandomGen.nextFloat() < mComputerMoveProbability) {
                doAI();
            }

            moveBall(ball);
        }
    }

    private void moveBall(Ball mBall) {
        mBall.cx += mBall.dx;
        mBall.cy += mBall.dy;

        if (mBall.cy < mBall.radius) {
            mBall.cy = mBall.radius;
        } else if (mBall.cy + mBall.radius >= mCanvasHeight) {
            mBall.cy = mCanvasHeight - mBall.radius - 1;
        }
    }

    private int cornerCount(boolean a, boolean b, boolean c, boolean d) {
        int corners = 0;
        if (a) {corners++;}
        if (b) {corners++;}
        if (c) {corners++;}
        if (d) {corners++;}
        return corners;
    }

    /**
     * Move the computer paddle to hit the ball.
     */
    private void doAI() {
        if (mComputerPlayer.bounds.top > mBalls.get(0).cy) {
            // move up
            movePlayer(mComputerPlayer,
                    mComputerPlayer.bounds.left,
                    mComputerPlayer.bounds.top - PHYS_PADDLE_SPEED);
        } else if (mComputerPlayer.bounds.top + mComputerPlayer.paddleHeight < mBalls.get(0).cy) {
            // move down
            movePlayer(mComputerPlayer,
                    mComputerPlayer.bounds.left,
                    mComputerPlayer.bounds.top + PHYS_PADDLE_SPEED);
        }
    }

    private boolean ballCollidedWithLeftWall(Ball mBall) {
        return mBall.cx <= mBall.radius;
    }

    private boolean ballCollidedWithRightWall(Ball mBall) {
        return mBall.cx + mBall.radius >= mCanvasWidth - 1;
    }

    private boolean ballCollidedWithTopOrBottomWall(Ball mBall) {
        return mBall.cy <= mBall.radius || mBall.cy + mBall.radius >= mCanvasHeight - 1;
    }

    /**
     * Draws the score, paddles and the ball.
     */
    private void updateDisplay(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        canvas.drawRect(0, 0, mCanvasWidth, mCanvasHeight, mCanvasBoundsPaint);

        final int middle = mCanvasWidth / 2;
        canvas.drawLine(middle, 1, middle, mCanvasHeight - 1, mMedianLinePaint);

        setScoreText(mHumanPlayer.score + "    " + mComputerPlayer.score);

        handleHit(mHumanPlayer);
        handleHit(mComputerPlayer);

        canvas.drawRoundRect(mHumanPlayer.bounds, 5, 5, mHumanPlayer.paint);
        canvas.drawRoundRect(mComputerPlayer.bounds, 5, 5, mComputerPlayer.paint);
        for (int i=0;i<mBalls.size();i++) {
            Ball ball = mBalls.get(i);
            canvas.drawCircle(ball.cx, ball.cy, ball.radius, ball.paint);
        }
        for (int j=0;j<mBricks.size();j++) {
            Brick brick = mBricks.get(j);
            canvas.drawRect(brick.getCoords(), brick.paint);
        }
    }

    private void handleHit(Player player) {
        if (player.collision > 0) {
            player.paint.setShadowLayer(player.paddleWidth / 2,
                    0, 0, player.paint.getColor());
        } else {
            player.paint.setShadowLayer(0, 0, 0, 0);
        }
    }

    /**
     * Reset players and ball position for a new round.
     */
    private void setupNewRound() {
        movePlayer(mHumanPlayer,
                2,
                (mCanvasHeight - mHumanPlayer.paddleHeight) / 2);

        movePlayer(mComputerPlayer,
                mCanvasWidth - mComputerPlayer.paddleWidth - 2,
                (mCanvasHeight - mComputerPlayer.paddleHeight) / 2);
        Ball mBall = mBalls.get(0);
        mBall.cx = mCanvasWidth / 8;
        mBall.cy = mCanvasHeight / 2;
        mBall.dx = -PHYS_BALL_SPEED;
        mBall.dy = 0;

        Paint bPaint = new Paint();
        bPaint.setAntiAlias(true);
        bPaint.setColor(Color.CYAN);

        float midX = mCanvasWidth/2;
        float midY = mCanvasHeight/2;
        Log.d("Ball Creation",
                "X-Coord: " + Float.toString(midX)+ " Y-Coord: " + Float.toString(midY));

        mBricks.clear();

        for (int i=0;i<((1.3*midX)/(2*BRICK_WIDTH));i++) {
            for (int j=0;j<((1.3*midY)/(2*BRICK_HEIGHT));j++) {
                if (mRandomGen.nextFloat() > .3) {
                    mBricks.add(new Brick(new RectF
                            (midX+i*BRICK_WIDTH, midY+j*BRICK_HEIGHT,
                            midX+(i+1)*BRICK_WIDTH, midY+(j+1)*BRICK_HEIGHT), bPaint));
                    mBricks.add(new Brick(new RectF
                            (midX-(i+1)*BRICK_WIDTH, midY+j*BRICK_HEIGHT,
                            midX-i*BRICK_WIDTH, midY+(j+1)*BRICK_HEIGHT), bPaint));
                    mBricks.add(new Brick(new RectF
                            (midX+i*BRICK_WIDTH, midY-(j+1)*BRICK_HEIGHT,
                            midX+(i+1)*BRICK_WIDTH, midY-j*BRICK_HEIGHT), bPaint));
                    mBricks.add(new Brick(new RectF
                            (midX-(i+1)*BRICK_WIDTH, midY-(j+1)*BRICK_HEIGHT,
                            midX-i*BRICK_WIDTH, midY-j*BRICK_HEIGHT), bPaint));
                }
            }
        }

    }

    private void setStatusText(String text) {
        Message msg = mStatusHandler.obtainMessage();
        Bundle b = new Bundle();
        b.putString("text", text);
        b.putInt("vis", View.VISIBLE);
        msg.setData(b);
        mStatusHandler.sendMessage(msg);
    }

    private void hideStatusText() {
        Message msg = mStatusHandler.obtainMessage();
        Bundle b = new Bundle();
        b.putInt("vis", View.INVISIBLE);
        msg.setData(b);
        mStatusHandler.sendMessage(msg);
    }

    private void setScoreText(String text) {
        Message msg = mScoreHandler.obtainMessage();
        Bundle b = new Bundle();
        b.putString("text", text);
        msg.setData(b);
        mScoreHandler.sendMessage(msg);
    }

    private void movePlayer(Player player, float left, float top) {
        if (left < 2) {
            left = 2;
        } else if (left + player.paddleWidth >= mCanvasWidth - 2) {
            left = mCanvasWidth - player.paddleWidth - 2;
        }
        if (top < 0) {
            top = 0;
        } else if (top + player.paddleHeight >= mCanvasHeight) {
            top = mCanvasHeight - player.paddleHeight - 1;
        }
        player.bounds.offsetTo(left, top);
    }

    private boolean collision(Player player, Ball ball) {
        return player.bounds.intersects(
                ball.cx - ball.radius,
                ball.cy - ball.radius,
                ball.cx + ball.radius,
                ball.cy + ball.radius);
    }

    private int collision(Ball ball, Brick brick) {
        RectF coords = brick.getCoords();
        RectF intersection = new RectF(coords);
        if (intersection.intersect(
                ball.cx - ball.radius,
                ball.cy - ball.radius,
                ball.cx + ball.radius,
                ball.cy + ball.radius)) {
            Log.d("Collision Detection", "Possible Collision");
            if (ball.cx <= coords.left) {
                if (ball.cy <= coords.top) {
                    if (distance(ball.cx, ball.cy, coords.left, coords.top) > ball.radius) {
                        return -1;
                    }
                    Log.d("Collision Detection", "Top-Left Collision Detected.");
                    return TOP_LEFT_HIT;
                }
                if (ball.cy >= coords.bottom) {
                    if (distance(ball.cx, ball.cy, coords.left, coords.bottom) > ball.radius) {
                        return -1;
                    }
                    Log.d("Collision Detection", "Bottom-Left Collision Detected.");
                    return BOTTOM_LEFT_HIT;
                }
            }
            if (ball.cx >= coords.right) {
                if (ball.cy <= coords.top) {
                    if (distance(ball.cx, ball.cy, coords.right, coords.top) > ball.radius) {
                        return -1;
                    }
                    Log.d("Collision Detection", "Top-Right Collision Detected.");
                    return TOP_RIGHT_HIT;
                }
                if (ball.cy >= coords.bottom) {
                    if (distance(ball.cx, ball.cy, coords.right, coords.bottom) > ball.radius) {
                        return -1;
                    }
                    Log.d("Collision Detection", "Bottom-Right Collision Detected.");
                    return BOTTOM_RIGHT_HIT;
                }
            }
            if (intersection.height() > intersection.width()) {
                Log.d("Collision Detection", "Top-Bottom Collision detected.");
                return TOP_BOTTOM_HIT;
            }
            Log.d("Collision Detection", "Left-Right Collision detected.");
            return LEFT_RIGHT_HIT;
        }
        return -1;
    }

    private double distance(float x1, float y1, float x2, float y2) {
        return Math.sqrt(Math.pow(Math.abs(x1 - x2), 2) + Math.pow(Math.abs(y1 - y2), 2));
    }

    /**
     * Compute ball direction after collision with player paddle.
     */
    private void handleCollision(Player player, Ball ball) {
        float relativeIntersectY = player.bounds.top + player.paddleHeight / 2 - ball.cy;
        float normalizedRelativeIntersectY = relativeIntersectY / (player.paddleHeight / 2);
        double bounceAngle = normalizedRelativeIntersectY * PHYS_MAX_BOUNCE_ANGLE;

        ball.dx = (float) (-Math.signum(ball.dx) * PHYS_BALL_SPEED * Math.cos(bounceAngle));
        ball.dy = (float) (PHYS_BALL_SPEED * -Math.sin(bounceAngle));

        if (player == mHumanPlayer) {
            ball.cx = mHumanPlayer.bounds.right + ball.radius;
        } else {
            ball.cx = mComputerPlayer.bounds.left - ball.radius;
        }
    }
}