/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Doubly linked list of primitive ints (first/last references, no sentinel).
 * merge() is a static helper; InsertionSort() pulls values off an unsorted
 * list and inserts each into a sorted list, scanning backwards from the end.
 */
public class LinkedList {

    private static final class Link {
        final int key;
        Link before;
        Link after;

        Link(int key) {
            this.key = key;
        }
    }

    private Link first;
    private Link last;
    private int count;

    public void addFirst(int key) {
        Link l = new Link(key);
        l.after = first;
        if (first != null) {
            first.before = l;
        } else {
            last = l;
        }
        first = l;
        count++;
    }

    public void addLast(int key) {
        Link l = new Link(key);
        l.before = last;
        if (last != null) {
            last.after = l;
        } else {
            first = l;
        }
        last = l;
        count++;
    }

    public int getFirst() {
        return first.key;
    }

    public void removeFirst() {
        first = first.after;
        if (first == null) {
            last = null;
        } else {
            first.before = null;
        }
        count--;
    }

    public boolean isEmpty() {
        return first == null;
    }

    public int getSize() {
        return count;
    }

    public static LinkedList merge(LinkedList a, LinkedList b) {
        LinkedList out = new LinkedList();
        while (!a.isEmpty() && !b.isEmpty()) {
            if (a.getFirst() < b.getFirst()) {
                out.addLast(a.getFirst());
                a.removeFirst();
            } else {
                out.addLast(b.getFirst());
                b.removeFirst();
            }
        }
        for (; !a.isEmpty(); a.removeFirst()) {
            out.addLast(a.getFirst());
        }
        for (; !b.isEmpty(); b.removeFirst()) {
            out.addLast(b.getFirst());
        }
        return out;
    }

    public void MergeSort() {
        Queue pending = new Queue();
        for (Link l = first; l != null; l = l.after) {
            LinkedList unit = new LinkedList();
            unit.addFirst(l.key);
            pending.enqueue(unit);
        }
        while (pending.size() > 1) {
            LinkedList x = pending.dequeue();
            LinkedList y = pending.dequeue();
            pending.enqueue(merge(x, y));
        }
        if (pending.size() == 1) {
            LinkedList done = pending.dequeue();
            first = done.first;
            last = done.last;
            count = done.count;
        }
    }

    /** Inserts key keeping this (sorted) list ordered; searches from the back. */
    private void insertSorted(int key) {
        Link spot = last;
        while (spot != null && spot.key > key) {
            spot = spot.before;
        }
        if (spot == null) {
            addFirst(key);
        } else if (spot == last) {
            addLast(key);
        } else {
            Link l = new Link(key);
            l.before = spot;
            l.after = spot.after;
            spot.after.before = l;
            spot.after = l;
            count++;
        }
    }

    public void InsertionSort() {
        LinkedList sorted = new LinkedList();
        while (!isEmpty()) {
            sorted.insertSorted(getFirst());
            removeFirst();
        }
        first = sorted.first;
        last = sorted.last;
        count = sorted.count;
    }

    public boolean isSorted() {
        if (first == null) {
            return true;
        }
        int previous = first.key;
        for (Link l = first.after; l != null; l = l.after) {
            if (l.key < previous) {
                return false;
            }
            previous = l.key;
        }
        return true;
    }
}
