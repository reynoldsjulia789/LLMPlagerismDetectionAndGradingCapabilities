// here in Tester java we want to builds two identical linked list
// with 20,000 randoms integers, sorts one with merge sort and the other
// with insertion sort messures and prints the time each takes
// and verifies both lists are sorted and unchanged in size
// we declare our tester class it can be accessed from other classes
public class Tester {
    public static void main(String[] args) {
        // first we  create two separate linkedList objects A and A2
        // A will be used to test mergedSort and A2 will be used to test
        // insertion , using two list ensures both sorts operate on identical data
        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
       // here we define how many random integers will be generated
        // and inseted into each list
        int randomListSize = 20000; // 20,000 as required we can do a million as will lol


        // we create a loop that runs 20,000 times
        for (int i = 0; i < randomListSize; i++) {
            // Math.random generates a double in the range of [0,1)
            // muliplying by 300000 gives a number from 0 to 2,999,999
            int randomInt = (int)(Math.random() * 3000000);
            // each random integers is appended to the end of both lists using
            // addlast
            A.addLast(randomInt);
            A2.addLast(randomInt);
        }


        // we do the measure mergesort time here
        // saves the current time in millisecounds before sorting
        // used to measure
        double then = System.currentTimeMillis();
        // sorts the linked list A using our mergeSort() implementation
        A.MergeSort();
        // end time here and svae the current time after sorting
        double now = System.currentTimeMillis();
        //here we first pring the elapsed time = end - start

        System.out.println("Time cost in milliseconds for mergesort " + (double)(now - then));
        System.out.println(A.isSorted());
        // it will print true if the list is sorted
        System.out.println("Size of list A is: " + A.getSize());
        // prints the number of elements in the list should still be 20,000 if
        // mergsort preserve size

        // here we measure the insertion sort performance
        // we use same structure as mergeSort
        then = System.currentTimeMillis();
        // we call our InsertionSort() method on the list A2 here
        A2.InsertionSort();
        now = System.currentTimeMillis();
        // here prints the elapsed time and verifies the list is sorted
        // and complete this

        System.out.println("Time cost in milliseconds for insertionsort " + (double)(now - then));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());
    }
}
