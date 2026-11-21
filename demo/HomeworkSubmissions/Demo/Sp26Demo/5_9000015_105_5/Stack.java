/* Demo submission (synthetic toy data, not student work): stack using ArrayDeque. */
import java.util.ArrayDeque;
import java.util.Deque;

public class Stack {
    private final Deque<Integer> store = new ArrayDeque<>();

    public void push(int value) {
        store.push(value);
    }

    public int pop() {
        Integer v = store.poll();
        if (v == null) {
            throw new IllegalStateException("stack is empty");
        }
        return v;
    }

    public int peek() {
        Integer v = store.peek();
        if (v == null) {
            throw new IllegalStateException("stack is empty");
        }
        return v;
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    public int size() {
        return store.size();
    }
}
