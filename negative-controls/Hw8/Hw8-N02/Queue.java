/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Generic FIFO queue backed by a singly linked chain of its own nodes.
 */
public class Queue<E> {

    private final class Cell {
        final E value;
        Cell next;

        Cell(E value) {
            this.value = value;
        }
    }

    private Cell first;
    private Cell last;
    private int n;

    public void enqueue(E value) {
        Cell c = new Cell(value);
        if (n == 0) {
            first = c;
        } else {
            last.next = c;
        }
        last = c;
        n++;
    }

    public E dequeue() {
        if (n == 0) {
            throw new java.util.NoSuchElementException("empty queue");
        }
        E value = first.value;
        first = first.next;
        n--;
        if (n == 0) {
            last = null;
        }
        return value;
    }

    public int size() {
        return n;
    }

    public boolean isEmpty() {
        return n == 0;
    }
}
