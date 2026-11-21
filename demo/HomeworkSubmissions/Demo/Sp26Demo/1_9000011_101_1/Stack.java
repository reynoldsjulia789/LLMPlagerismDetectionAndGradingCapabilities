/* Demo submission (synthetic toy data, not student work): array-backed stack. */
public class Stack {
    private int[] buffer = new int[8];
    private int n;

    public void push(int value) {
        if (n == buffer.length) {
            int[] bigger = new int[buffer.length * 2];
            for (int i = 0; i < n; i++) {
                bigger[i] = buffer[i];
            }
            buffer = bigger;
        }
        buffer[n++] = value;
    }

    public int pop() {
        if (isEmpty()) {
            throw new RuntimeException("Empty stack");
        }
        return buffer[--n];
    }

    public int peek() {
        if (isEmpty()) {
            throw new RuntimeException("Empty stack");
        }
        return buffer[n - 1];
    }

    public boolean isEmpty() {
        return n == 0;
    }

    public int size() {
        return n;
    }

    public int sum() {
        int total = 0;
        Stack copy = new Stack();
        while (!isEmpty()) {
            int v = pop();
            total += v;
            copy.push(v);
        }
        while (!copy.isEmpty()) {
            push(copy.pop());
        }
        return total;
    }
}
