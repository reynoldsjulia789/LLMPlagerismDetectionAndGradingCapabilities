package aiaa.cam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Loads prompt templates & JSON schemas
 */
public class CAMPromptLoader
{
    private final String systemPrompt;
    private final String userPromptTemplate;
    private final String gradingJsonSchema;

    public CAMPromptLoader(Path promptsDir) throws IOException
    {
        this.systemPrompt = Files.readString(promptsDir.resolve("cam_grading_system.txt"));
        this.userPromptTemplate = Files.readString(promptsDir.resolve("cam_grading_user.txt"));
        this.gradingJsonSchema = Files.readString(promptsDir.resolve("cam_grading_schema.json"));
    }

    public String getSystemPrompt()
    {
        return systemPrompt;
    }

    public String getGradingJsonSchema()
    {
        return gradingJsonSchema;
    }

    public String buildUserPrompt(String assignment, String studentCode)
    {
        return userPromptTemplate
                .replace("{{ASSIGNMENT}}", assignment)
                .replace("{{RUBRIC}}", getRubric(assignment))
                .replace("{{CODE}}", studentCode);
    }

    public String readStudentSourceCode(Path studentDir) throws IOException
    {
        StringBuilder code = new StringBuilder();

        try (Stream<Path> files = Files.walk(studentDir))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList())
            {
                code.append("// === FILE: ").append(file.getFileName()).append(" ===\n");
                code.append(Files.readString(file)).append("\n\n");
            }
        }
        return code.toString();
    }

    private String getRubric(String assignment)
    {
        return switch (assignment)
        {
            case "Hw1" -> "Singly Linked List with dummy head. Required methods: size, isEmpty, removeFirst, contains, remove(Object), removeAllCopies, interleave, add(int, Object), get(int), remove(int), add(Object). Check null handling, bounds, size updates, consecutive removals, and preserving both lists in interleave.";
            case "Hw2" -> "Circular Doubly Linked List with dummy head. Required methods: addLast, subListOfSmallerValues, removeStartingAtBack, lastIndexOf, retainAll, insertionSort. Check forward/backward circular links, empty sentinel invariants, duplicates, and null handling.";
            case "Hw8" -> "Separate LinkedList and Queue classes. Required methods: Queue (enqueue, dequeue, isEmpty), MergeSort (queue of single-node lists + O(1) append merge), InsertionSort, isSorted (O(n) single scan). Check element preservation and time complexity.";
            default -> "Grade each method from 0 to 100 based on correctness, edge cases, and efficiency.";
        };
    }
}
