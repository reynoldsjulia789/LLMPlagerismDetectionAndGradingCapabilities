/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Queue: FIFO queue of LinkedList objects, implemented with its own nodes
 * (separate from the LinkedList class, as the assignment requires).
 */
public class Queue {

    private static class Slot {
        final LinkedList item;
        Slot next;

        Slot(LinkedList item) {
            this.item = item;
        }
    }

    private Slot front;
    private Slot rear;
    private int count;

    public void enqueue(LinkedList list) {
        Slot s = new Slot(list);
        if (rear == null) {
            front = s;
        } else {
            rear.next = s;
        }
        rear = s;
        count++;
    }

    public LinkedList dequeue() {
        if (front == null) {
            throw new IllegalStateException("queue is empty");
        }
        LinkedList item = front.item;
        front = front.next;
        if (front == null) {
            rear = null;
        }
        count--;
        return item;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }
}
