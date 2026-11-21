/* Demo submission (synthetic toy data, not student work): stack on top of an ArrayList. */
import java.util.ArrayList;

public class Stack {
    private final ArrayList<Integer> list = new ArrayList<>();

    public void push(int value) {
        list.add(value);
    }

    public int pop() {
        checkNotEmpty();
        return list.remove(list.size() - 1);
    }

    public int peek() {
        checkNotEmpty();
        return list.get(list.size() - 1);
    }

    private void checkNotEmpty() {
        if (list.isEmpty()) {
            throw new RuntimeException("Stack underflow");
        }
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public int size() {
        return list.size();
    }
}
