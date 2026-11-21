/* Demo submission (synthetic toy data, not student work): linked stack. */
public class Stack {
    private static class Cell {
        int value;
        Cell below;
        Cell(int value, Cell below) {
            this.value = value;
            this.below = below;
        }
    }

    private Cell top;
    private int n;

    public void push(int value) {
        top = new Cell(value, top);
        n++;
    }

    public int pop() {
        int v = peek();
        top = top.below;
        n--;
        return v;
    }

    public int peek() {
        if (top == null) {
            throw new IllegalStateException("Empty!");
        }
        return top.value;
    }

    public boolean isEmpty() {
        return top == null;
    }

    public int size() {
        return n;
    }

    public void clear() {
        while (!isEmpty()) {
            pop();
        }
    }
}
