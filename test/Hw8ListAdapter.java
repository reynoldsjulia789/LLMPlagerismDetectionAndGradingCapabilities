import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class Hw8ListAdapter
{
    private final Object   _object;
    private final Class<?> _class;

    private static final String[] CANDIDATE_CLASSES = {
            "LinkedList", "CDoublyLinkedList", "CircularDoublyLinkedList", "MyLinkedList"
    };

    public Hw8ListAdapter()
    {
        Class<?> found = null;

        for (String name : CANDIDATE_CLASSES)
        {
            try
            {
                found = Class.forName(name);
                break;
            }
            catch (ClassNotFoundException ignored) {}
        }

        if (found == null)
        {
            throw new RuntimeException("No valid List class found in submission.");
        }

        this._class = found;

        try
        {
            this._object = _class.getDeclaredConstructor().newInstance();
        }
        catch (Exception caught)
        {
            throw new RuntimeException("Failed to instantiate " + _class.getName(), caught);
        }
    }

    private Method findMethod(int paramCount, String... nameOptions)
    {
        for (String name : nameOptions)
        {
            for (Method m : _class.getMethods())
            {
                if (m.getName().equalsIgnoreCase(name) && m.getParameterCount() == paramCount)
                {
                    return m;
                }
            }
        }

        throw new RuntimeException("Method '" + nameOptions[0] + "' not found in " + _class.getName());
    }

    private Object invoke(String[] nameOptions, Object... args)
    {
        try
        {
            Method m = findMethod(args.length, nameOptions);
            return m.invoke(_object, args);
        }
        catch (Exception caught)
        {
            Throwable cause = (caught.getCause() != null) ? caught.getCause() : caught;

            if (cause instanceof RuntimeException) throw (RuntimeException) cause;

            throw new RuntimeException(cause);
        }
    }

    public void addFirst(Object val)
    {
        invoke(new String[] {"addFirst"}, val);
    }

    public void addLast(Object val)
    {
        invoke(new String[] {"addLast", "add"}, val);
    }

    public Object removeFirst()
    {
        return invoke(new String[] {"removeFirst"});
    }

    public void mergeSort()
    {
        invoke(new String[] {"MergeSort", "mergeSort"});
    }

    public void insertionSort()
    {
        invoke(new String[] {"InsertionSort", "insertionSort"});
    }

    public boolean isSorted()
    {
        return (boolean) invoke(new String[] {"isSorted"});
    }

    public boolean isEmpty()
    {
        try
        {
            return (boolean) invoke(new String[]{"isEmpty"});
        }
        catch (RuntimeException ignored)
        {
            int size = (int) invoke(new String[]{"size", "getSize"});
            return size == 0;
        }
    }

    public List<Object> getList()  // not all submissions appear to have toString, so this is a workaround
    {
        List<Object> result      = new ArrayList<>();
        int          safetyLimit = 250000;

        while (!isEmpty() && (result.size() < safetyLimit)) // prevents infinite looping
        {
            try
            {
                result.add(removeFirst());
            }
            catch (Exception ignored)
            {
                break;
            }
        }

        return result;
    }

    @Override
    public String toString()
    {
        return _object.toString();
    }
}
