import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const repositoryRoot = process.cwd();
const backendRoot = path.join(repositoryRoot, 'backend');

const redisNonceModules = [
  'storage-service',
  'datasource-service',
  'interface-service',
  'trigger-service',
  'publication-service',
];

const explicitRedisDependency = /<dependency>\s*<groupId>org[.]springframework[.]boot<\/groupId>\s*<artifactId>spring-boot-starter-data-redis<\/artifactId>\s*<\/dependency>/m;
const packagedRedisAssertion = /jar tf \/app\/[^\s]+\/target\/app[.]jar \| grep -q '\^BOOT-INF\/lib\/spring-data-redis-/;
const secureLogDirectory = 'mkdir -p /app/logs && chown 1001:1001 /app/logs && chmod 0750 /app/logs';

for (const moduleName of redisNonceModules) {
  const pom = fs.readFileSync(path.join(backendRoot, moduleName, 'pom.xml'), 'utf8');
  assert.match(
    pom,
    explicitRedisDependency,
    `${moduleName} must declare its distributed nonce-store Redis runtime explicitly`,
  );

  const dockerfile = fs.readFileSync(path.join(backendRoot, moduleName, 'Dockerfile'), 'utf8');
  assert.match(
    dockerfile,
    packagedRedisAssertion,
    `${moduleName} image build must prove spring-data-redis is packaged`,
  );
}

const fileLoggingModules = fs.readdirSync(backendRoot, {withFileTypes: true})
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .filter((moduleName) => {
    const application = path.join(backendRoot, moduleName, 'src', 'main', 'resources', 'application.yml');
    return fs.existsSync(application)
      && /name:\s*logs\/[A-Za-z0-9_-]+[.]log/.test(fs.readFileSync(application, 'utf8'));
  });

assert.ok(fileLoggingModules.length > 0, 'expected to discover file-logging Java services');
for (const moduleName of fileLoggingModules) {
  const dockerfilePath = path.join(backendRoot, moduleName, 'Dockerfile');
  assert.ok(fs.existsSync(dockerfilePath), `${moduleName} must have a runtime Dockerfile`);
  const dockerfile = fs.readFileSync(dockerfilePath, 'utf8');
  const directoryIndex = dockerfile.indexOf(secureLogDirectory);
  const userIndex = dockerfile.indexOf('USER appuser');
  assert.ok(directoryIndex >= 0, `${moduleName} must provision /app/logs as 1001:1001 mode 0750`);
  assert.ok(userIndex > directoryIndex, `${moduleName} must provision /app/logs before dropping privileges`);
  assert.doesNotMatch(dockerfile.slice(userIndex), /^USER root$/m, `${moduleName} must remain non-root`);
}

const gatewayWeb = fs.readFileSync(
  path.join(backendRoot, 'common-lib', 'src', 'main', 'java', 'com', 'apimarketplace', 'common', 'web', 'GatewayWebAutoConfiguration.java'),
  'utf8',
);
assert.doesNotMatch(gatewayWeb, /org[.]springframework[.]data[.]redis/);
assert.doesNotMatch(gatewayWeb, /ObjectProvider<StringRedisTemplate>/);

const redisAutoConfiguration = fs.readFileSync(
  path.join(backendRoot, 'common-lib', 'src', 'main', 'java', 'com', 'apimarketplace', 'common', 'web', 'GatewayRedisNonceStoreAutoConfiguration.java'),
  'utf8',
);
assert.match(redisAutoConfiguration, /@ConditionalOnClass\(\{RedisConnectionFactory[.]class, StringRedisTemplate[.]class\}\)/);
assert.match(redisAutoConfiguration, /@ConditionalOnSingleCandidate\(RedisConnectionFactory[.]class\)/);
assert.match(redisAutoConfiguration, /new StringRedisTemplate\(connectionFactory\)/);

const compose = fs.readFileSync(path.join(repositoryRoot, 'docker', 'docker-compose.cloud.yml'), 'utf8');
assert.match(compose, /GATEWAY_FILTER_REQUIRE_DISTRIBUTED_NONCE_STORE:\s*"true"/);
assert.match(compose, /paid-monolith-truststore[.]p12:ro/);

console.log(`Validated distributed nonce packaging for ${redisNonceModules.length} services.`);
console.log(`Validated non-root log directories for ${fileLoggingModules.length} services.`);
