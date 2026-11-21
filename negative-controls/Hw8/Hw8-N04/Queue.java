/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Queue of LinkedList objects with front/rear references.
 */
public class Queue {

    private class Entry {
        LinkedList list;
        Entry behind;
    }

    private Entry front;
    private Entry rear;
    private int length;

    public void enqueue(LinkedList list) {
        Entry e = new Entry();
        e.list = list;
        if (isEmpty()) {
            front = rear = e;
        } else {
            rear.behind = e;
            rear = e;
        }
        length++;
    }

    public LinkedList dequeue() {
        if (isEmpty()) {
            return null;
        }
        LinkedList list = front.list;
        front = front.behind;
        if (front == null) {
            rear = null;
        }
        length--;
        return list;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public int size() {
        return length;
    }
}
