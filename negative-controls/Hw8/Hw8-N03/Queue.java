/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Queue implemented as a circular array that doubles when full.
 */
public class Queue {

    private Object[] items = new Object[16];
    private int front = 0;
    private int count = 0;

    public void enqueue(Object item) {
        if (count == items.length) {
            Object[] bigger = new Object[items.length * 2];
            for (int i = 0; i < count; i++) {
                bigger[i] = items[(front + i) % items.length];
            }
            items = bigger;
            front = 0;
        }
        items[(front + count) % items.length] = item;
        count++;
    }

    public Object dequeue() {
        if (count == 0) {
            throw new RuntimeException("Queue underflow");
        }
        Object item = items[front];
        items[front] = null;
        front = (front + 1) % items.length;
        count--;
        return item;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }
}
