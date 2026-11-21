/* Demo submission (synthetic toy data, not student work): linked stack. */
public class Stack {
    private static class Link {
        int value;
        Link below;
        Link(int value, Link below) {
            this.value = value;
            this.below = below;
        }
    }

    private Link first;
    private int size;

    public void push(int value) {
        first = new Link(value, first);
        size++;
    }

    public int pop() {
        int v = peek();
        first = first.below;
        size--;
        return v;
    }

    public int peek() {
        if (first == null) {
            throw new IllegalStateException("no elements");
        }
        return first.value;
    }

    public boolean isEmpty() {
        return first == null;
    }

    public int size() {
        return size;
    }
}
