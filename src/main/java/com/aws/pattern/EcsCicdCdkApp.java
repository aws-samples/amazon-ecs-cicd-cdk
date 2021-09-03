package com.aws.pattern;

import com.aws.pattern.cicd.CicdBlueGreenStack;
import com.aws.pattern.infrastructure.EcsInfrastructureStack;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.StackProps;

public class EcsCicdCdkApp {
	
	
	  public static void main(final String[] args) {
		  App app = new App();
		  EcsInfrastructureStack ecsInfraStack = new EcsInfrastructureStack(app, "MyEcsClusterInfrastructureCdkStack", StackProps.builder().build());
		  CicdBlueGreenStack cicdStack = new CicdBlueGreenStack(app, "MyCICDBlueGreenStack", StackProps.builder().build(), ecsInfraStack);
		  app.synth();
	  }

}
