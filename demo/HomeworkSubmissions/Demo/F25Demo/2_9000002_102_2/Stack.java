/* Demo submission (synthetic toy data, not student work): array-backed stack. */
public class Stack {
    private int[] arr = new int[2];
    private int sz;

    public void push(int value) {
        if (sz == arr.length) {
            int[] bigger = new int[arr.length * 2];
            for (int i = 0; i < sz; i++) {
                bigger[i] = arr[i];
            }
            arr = bigger;
        }
        arr[sz++] = value;
    }

    public int pop() {
        if (isEmpty()) {
            throw new RuntimeException("nothing to pop");
        }
        return arr[--sz];
    }

    public int peek() {
        if (isEmpty()) {
            throw new RuntimeException("nothing to pop");
        }
        return arr[sz - 1];
    }

    public boolean isEmpty() {
        return sz == 0;
    }

    public int size() {
        return sz;
    }

    public void clear() {
        while (!isEmpty()) {
            pop();
        }
    }
}
