package aiaa.llm;

import java.util.function.Function;

/**
 * Offline adapter for development and tests: no network, no cost, deterministic.
 * The responder turns a request into the raw text a real model would have returned.
 * Results produced with this adapter are NOT research results.
 */
public final class MockAdapter implements ModelAdapter {

    private final String modelId;
    private final Function<ModelRequest, String> responder;

    public MockAdapter(String modelId, Function<ModelRequest, String> responder) {
        this.modelId = modelId;
        this.responder = responder;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        String text = responder.apply(request);
        long approxIn = (request.systemPrompt().length() + request.userPrompt().length()) / 4;
        return new ModelResponse(text, modelId, modelId, approxIn, text.length() / 4, 0, 0);
    }
}
