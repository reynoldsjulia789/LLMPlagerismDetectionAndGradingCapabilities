/* Demo negative control (synthetic toy data): written independently of the demo submissions. */
public class Stack {
    private final java.util.List<int[]> chunks = new java.util.LinkedList<>();
    private int used3;
    private static final int CHUNK = 5;

    public void push(int value) {
        if (used3 % CHUNK == 0) {
            chunks.add(0, new int[CHUNK]);
        }
        chunks.get(0)[used3 % CHUNK] = value;
        used3++;
    }

    public int pop() {
        int v = peek();
        used3--;
        if (used3 % CHUNK == 0) {
            chunks.remove(0);
        }
        return v;
    }

    public int peek() {
        if (used3 == 0) {
            throw new java.util.NoSuchElementException("control 3: empty");
        }
        return chunks.get(0)[(used3 - 1) % CHUNK];
    }

    public boolean isEmpty() {
        return used3 == 0;
    }

    public int size() {
        return used3;
    }
}
