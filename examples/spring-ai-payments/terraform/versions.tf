terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.18.0"
    }
    awscc = {
      source  = "hashicorp/awscc"
      version = ">= 1.60.0"
    }
    time = {
      source  = "hashicorp/time"
      version = ">= 0.11"
    }
  }
}

provider "aws" {
  region = var.region
}

provider "awscc" {
  region = var.region
}
