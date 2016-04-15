/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.dataflow.yarn.buildtests;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.yarn.DefaultYarnCloudAppService;
import org.springframework.cloud.deployer.spi.yarn.TaskLauncherStateMachine;
import org.springframework.cloud.deployer.spi.yarn.YarnCloudAppService;
import org.springframework.cloud.deployer.spi.yarn.YarnCloudAppService.CloudAppInstanceInfo;
import org.springframework.cloud.deployer.spi.yarn.YarnCloudAppService.CloudAppType;
import org.springframework.cloud.deployer.spi.yarn.YarnTaskLauncher;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.yarn.test.support.ContainerLogUtils;

/**
 * Integration tests for {@link YarnTaskLauncher}.
 *
 * Tests can be run in sts if project is build first. Build
 * prepares needed yarn files in expected paths.
 * $ mvn clean package -DskipTests
 *
 * @author Janne Valkealahti
 *
 */
public class TaskLauncherIT extends AbstractCliBootYarnClusterTests {

	private static final String GROUP_ID = "org.springframework.cloud.task.module";
	private String artifactVersion;
	private AnnotationConfigApplicationContext context;

	@Before
	public void setup() {
		artifactVersion = getEnvironment().getProperty("artifactVersion");
		context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles("yarn");
		context.register(TestYarnConfiguration.class);
		context.setParent(getApplicationContext());
		context.refresh();
	}

	@After
	public void clean() {
		if (context != null) {
			context.close();
		}
		context = null;
	}

	@Test
	@Ignore
	public void testTaskTimestamp() throws Exception {
		assertThat(context.containsBean("taskLauncher"), is(true));
		assertThat(context.getBean("taskLauncher"), instanceOf(YarnTaskLauncher.class));
		TaskLauncher deployer = context.getBean("taskLauncher", TaskLauncher.class);
		YarnCloudAppService yarnCloudAppService = context.getBean(YarnCloudAppService.class);

		MavenProperties m2Properties = new MavenProperties();
		m2Properties.setRemoteRepositories(new String[] {"https://repo.spring.io/libs-snapshot-local"});

		MavenResource resource = new MavenResource.Builder(m2Properties)
				.artifactId("timestamp-task")
				.groupId(GROUP_ID)
				.version(artifactVersion)
				.extension("jar")
				.classifier("exec")
				.build();

		AppDefinition definition = new AppDefinition("timestamp-task", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);
		String id = deployer.launch(request);
		assertThat(id, notNullValue());

		ApplicationId applicationId = assertWaitApp(2, TimeUnit.MINUTES, yarnCloudAppService);
		assertWaitFileContent(2, TimeUnit.MINUTES, applicationId, "Started TaskApplication");

		List<Resource> resources = ContainerLogUtils.queryContainerLogs(
				getYarnCluster(), applicationId);

		assertThat(resources, notNullValue());
		assertThat(resources.size(), is(4));
	}

	@Test
	@Ignore
	public void testTaskTimestampCancel() throws Exception {
		assertThat(context.containsBean("taskLauncher"), is(true));
		assertThat(context.getBean("taskLauncher"), instanceOf(YarnTaskLauncher.class));
		TaskLauncher deployer = context.getBean("taskLauncher", TaskLauncher.class);
		YarnCloudAppService yarnCloudAppService = context.getBean(YarnCloudAppService.class);

		MavenProperties m2Properties = new MavenProperties();
		m2Properties.setRemoteRepositories(new String[] {"https://repo.spring.io/libs-snapshot-local"});

		MavenResource resource = new MavenResource.Builder(m2Properties)
				.artifactId("timestamp-task")
				.groupId(GROUP_ID)
				.version(artifactVersion)
				.extension("jar")
				.classifier("exec")
				.build();

		Map<String, String> properties = new HashMap<String, String>();
		// let it run max 60 sec to get status and cancel.
		properties.put("repeat", "60");
		AppDefinition definition = new AppDefinition("timestamp-task", properties);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);
		String id = deployer.launch(request);
		assertThat(id, notNullValue());

		ApplicationId applicationId = assertWaitApp(2, TimeUnit.MINUTES, yarnCloudAppService);
		assertWaitFileContent(2, TimeUnit.MINUTES, applicationId, "Sleeping");

		assertThat(deployer.status(id).getState(), is(LaunchState.running));
		deployer.cancel(id);
		assertWaitFileContent(2, TimeUnit.MINUTES, applicationId, "Stopping beans");
		assertThat(deployer.status(id).getState(), is(LaunchState.unknown));

		List<Resource> resources = ContainerLogUtils.queryContainerLogs(
				getYarnCluster(), applicationId);

		assertThat(resources, notNullValue());
		assertThat(resources.size(), is(4));

		for (Resource res : resources) {
			File file = res.getFile();
			String content = ContainerLogUtils.getFileContent(file);
			if (file.getName().endsWith("stdout")) {
				assertThat(file.length(), greaterThan(0l));
			} else if (file.getName().endsWith("Container.stderr")) {
				assertThat("stderr with content: " + content, file.length(), is(0l));
			}
		}
	}

	private ApplicationId assertWaitApp(long timeout, TimeUnit unit, YarnCloudAppService yarnCloudAppService) throws Exception {
		ApplicationId applicationId = null;
		Collection<CloudAppInstanceInfo> instances;
		long end = System.currentTimeMillis() + unit.toMillis(timeout);

		do {
			instances = yarnCloudAppService.getInstances(CloudAppType.TASK);
			if (instances.size() == 1) {
				CloudAppInstanceInfo cloudAppInstanceInfo = instances.iterator().next();
				if (StringUtils.hasText(cloudAppInstanceInfo.getAddress())) {
					applicationId = ConverterUtils.toApplicationId(cloudAppInstanceInfo.getApplicationId());
					break;
				}
			}
			Thread.sleep(1000);
		} while (System.currentTimeMillis() < end);

		assertThat(applicationId, notNullValue());
		return applicationId;
	}

	@Configuration
	public static class TestYarnConfiguration {

		@Autowired
		private org.apache.hadoop.conf.Configuration configuration;

		@Autowired
		private Environment environment;

		@Bean
		public TaskExecutor yarnTaskLauncherTaskExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
			executor.setCorePoolSize(1);
			return executor;
		}

		@Bean
		public TaskLauncherStateMachine taskLauncherStateMachine(YarnCloudAppService yarnCloudAppService,
				TaskExecutor yarnTaskLauncherTaskExecutor, BeanFactory beanFactory) throws Exception {
			return new TaskLauncherStateMachine(yarnCloudAppService, yarnTaskLauncherTaskExecutor, beanFactory);
		}

		@Bean
		public TaskLauncher taskLauncher(YarnCloudAppService yarnCloudAppService,
				TaskLauncherStateMachine taskLauncherStateMachine) throws Exception {
			return new YarnTaskLauncher(yarnCloudAppService, taskLauncherStateMachine.buildStateMachine());
		}

		@Bean
		public YarnCloudAppService yarnCloudAppService() {
			ApplicationContextInitializer<?>[] initializers = new ApplicationContextInitializer<?>[] {
					new HadoopConfigurationInjectingInitializer(configuration) };
			String dataflowVersion = environment.getProperty("projectVersion");
			return new DefaultYarnCloudAppService(dataflowVersion, initializers);
		}
	}
}