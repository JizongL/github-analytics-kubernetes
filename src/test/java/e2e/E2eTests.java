package e2e;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.kubernetes.KubernetesClientProperties;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.BDDAssertions.then;
import static org.awaitility.Awaitility.await;

/**
 * @author Marcin Grzejszczak
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = E2eTests.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnableAutoConfiguration
public class E2eTests {

	private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass());

	@Value("${application.url}") String applicationUrl;
	@Value("${classpath:json/issue-created.json}") Resource json;
	@Autowired KubernetesClient client;
	@Autowired Config config;
	@Value("${kubernetes.minikube:true}") Boolean minikube;
	@Value("${paas.namespace}") String kubernetesNamespace;

	RestTemplate restTemplate = new RestTemplate();

	@Test
	public void shouldStoreAMessageWhenGithubDataWasReceivedViaMessaging()
			throws IOException {
		final Integer countOfEntries = countGithubData();
		log.info("Initial count is [" + countOfEntries + "]");

		ResponseEntity<String> response = callData();
		then(response.getStatusCode().is2xxSuccessful()).isTrue();
		then(response.getBody()).isNotNull();

		log.info("Awaiting proper count of github data");
		await().until(() -> countGithubData() > countOfEntries);
	}

	private ResponseEntity<String> callData() throws IOException {
		String uri = "http://" + githubWebhookUrl();
		log.info("Will send a request to [" + uri + "]");
		return this.restTemplate.exchange(RequestEntity
				.post(URI.create(uri))
				.contentType(MediaType.APPLICATION_JSON)
				.body(data()), String.class);
	}

	private String githubWebhookUrl() {
		List<ServicePort> ports = ports();
		if (this.minikube) {
			Integer port = ports.get(0).getNodePort();
			String host = URI.create(this.config.getMasterUrl()).getHost();
			return host + ":" + port;
		}
		return "github-webhook" + "." + this.kubernetesNamespace + ":" + ports.get(0).getPort();
	}

	private List<ServicePort> ports() {
		List<Service> githubWebhookSvc = this.client.services().withLabel("name", "github-webhook")
				.list().getItems();
		if (githubWebhookSvc.isEmpty()) {
			throw new IllegalStateException("No github-webhook service was found");
		}
		return githubWebhookSvc.get(0).getSpec().getPorts();
	}

	public String data() throws IOException {
		return new String(Files.readAllBytes(this.json.getFile().toPath()));
	}

	private Integer countGithubData() {
		Integer response = this.restTemplate
				.getForObject("http://" + this.applicationUrl + "/issues/count", Integer.class);
		log.info("Received response [" + response + "]");
		return response;
	}
}
