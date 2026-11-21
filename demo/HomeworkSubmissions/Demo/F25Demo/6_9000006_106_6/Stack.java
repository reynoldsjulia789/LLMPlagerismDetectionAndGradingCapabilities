/* Demo submission (synthetic toy data, not student work): stack using ArrayDeque. */
import java.util.ArrayDeque;
import java.util.Deque;

public class Stack {
    private final Deque<Integer> dq = new ArrayDeque<>();

    public void push(int value) {
        dq.push(value);
    }

    public int pop() {
        Integer v = dq.poll();
        if (v == null) {
            throw new IllegalStateException("Empty stack");
        }
        return v;
    }

    public int peek() {
        Integer v = dq.peek();
        if (v == null) {
            throw new IllegalStateException("Empty stack");
        }
        return v;
    }

    public boolean isEmpty() {
        return dq.isEmpty();
    }

    public int size() {
        return dq.size();
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
