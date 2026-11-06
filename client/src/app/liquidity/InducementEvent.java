package app.liquidity;

import app.common.Direction;

public class InducementEvent {
    public final int index;
    public final Direction expectedMove;
    public final double price;
    public final String type;
    public final int score;
    public final String note;

    public InducementEvent(int index, Direction expectedMove, double price, String type, int score, String note) {
        this.index = index;
        this.expectedMove = expectedMove;
        this.price = price;
        this.type = type;
        this.score = score;
        this.note = note;
    }
}
