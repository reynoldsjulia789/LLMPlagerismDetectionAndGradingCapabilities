/* Demo submission (synthetic toy data, not student work): array-backed stack. */
public class Stack {
    private int[] elems = new int[4];
    private int top;

    public void push(int value) {
        if (top == elems.length) {
            int[] bigger = new int[elems.length * 2];
            for (int i = 0; i < top; i++) {
                bigger[i] = elems[i];
            }
            elems = bigger;
        }
        elems[top++] = value;
    }

    public int pop() {
        if (isEmpty()) {
            throw new RuntimeException("stack is empty");
        }
        return elems[--top];
    }

    public int peek() {
        if (isEmpty()) {
            throw new RuntimeException("stack is empty");
        }
        return elems[top - 1];
    }

    public boolean isEmpty() {
        return top == 0;
    }

    public int size() {
        return top;
    }
}
