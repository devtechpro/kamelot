import type { StepKind } from '@/lib/interfaces/INodeData'

export type ParamType = 'string' | 'boolean' | 'integer' | 'password' | 'expression'

export type ComponentCategory =
  | 'Trigger'
  | 'HTTP'
  | 'File & IO'
  | 'Messaging'
  | 'Cloud'
  | 'Database'
  | 'Data Format'
  | 'Transformation'
  | 'Routing'
  | 'Error Handling'

export interface ComponentParam {
  name: string
  label: string
  type: ParamType
  required: boolean
  defaultValue?: string
  description: string
  /** If true, value is interpolated into the URI path, not as a query param */
  isUriPath?: boolean
}

export interface CamelComponentDef {
  scheme: string
  label: string
  description: string
  category: ComponentCategory
  stepKind: StepKind
  params: ComponentParam[]
  /** Template for rawStep when a new node is dropped from the palette */
  defaultRawStep: Record<string, unknown>
  /** Default URI for to/from endpoint nodes */
  defaultUri?: string
}

export const CAMEL_COMPONENT_REGISTRY: CamelComponentDef[] = [
  // ── Trigger ──────────────────────────────────────────────────────────────
  {
    scheme: 'timer',
    label: 'Timer',
    description: 'Fires at fixed intervals or a set number of times',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'timer:trigger',
    defaultRawStep: { uri: 'timer:trigger', parameters: { repeatCount: '1', delay: '0' } },
    params: [
      { name: 'timerName', label: 'Timer Name', type: 'string', required: true, defaultValue: 'trigger', description: 'Name of the timer', isUriPath: true },
      { name: 'period', label: 'Period (ms)', type: 'integer', required: false, defaultValue: '1000', description: 'Interval between fires in milliseconds' },
      { name: 'repeatCount', label: 'Repeat Count', type: 'integer', required: false, defaultValue: '1', description: 'Number of times to fire (0 = unlimited)' },
      { name: 'delay', label: 'Initial Delay (ms)', type: 'integer', required: false, defaultValue: '0', description: 'Delay before first fire' },
    ],
  },
  {
    scheme: 'quartz',
    label: 'Quartz Scheduler',
    description: 'Schedule using a Quartz cron expression',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'quartz:myJob',
    defaultRawStep: { uri: 'quartz:myJob', parameters: { cron: '0/5 * * * * ?' } },
    params: [
      { name: 'groupName', label: 'Job Name', type: 'string', required: true, defaultValue: 'myJob', description: 'Quartz job name', isUriPath: true },
      { name: 'cron', label: 'Cron Expression', type: 'string', required: true, defaultValue: '0/5 * * * * ?', description: 'Quartz cron expression' },
    ],
  },
  {
    scheme: 'rest',
    label: 'REST Receiver',
    description: 'Expose a REST endpoint to receive HTTP requests',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'rest:get:/api/resource',
    defaultRawStep: { uri: 'rest:get:/api/resource' },
    params: [
      { name: 'method', label: 'HTTP Method', type: 'string', required: true, defaultValue: 'get', description: 'HTTP verb (get, post, put, delete)', isUriPath: true },
      { name: 'path', label: 'Path', type: 'string', required: true, defaultValue: '/api/resource', description: 'REST path pattern', isUriPath: true },
    ],
  },
  {
    scheme: 'direct',
    label: 'Direct',
    description: 'Synchronous call between Camel routes in the same context',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'direct:start',
    defaultRawStep: { uri: 'direct:start' },
    params: [
      { name: 'name', label: 'Endpoint Name', type: 'string', required: true, defaultValue: 'start', description: 'Endpoint name', isUriPath: true },
    ],
  },
  {
    scheme: 'seda',
    label: 'SEDA',
    description: 'Asynchronous in-memory queue endpoint',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'seda:queue',
    defaultRawStep: { uri: 'seda:queue', parameters: { size: '1000' } },
    params: [
      { name: 'name', label: 'Queue Name', type: 'string', required: true, defaultValue: 'queue', description: 'SEDA queue name', isUriPath: true },
      { name: 'size', label: 'Queue Size', type: 'integer', required: false, defaultValue: '1000', description: 'Max queue capacity' },
      { name: 'concurrentConsumers', label: 'Concurrent Consumers', type: 'integer', required: false, defaultValue: '1', description: 'Number of concurrent consumers' },
    ],
  },
  {
    scheme: 'file',
    label: 'File Consumer',
    description: 'Poll a directory and consume new files',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'file:data/inbox',
    defaultRawStep: { uri: 'file:data/inbox', parameters: { noop: 'true' } },
    params: [
      { name: 'directoryName', label: 'Directory', type: 'string', required: true, defaultValue: 'data/inbox', description: 'Directory path to poll', isUriPath: true },
      { name: 'noop', label: 'No-op (read-only)', type: 'boolean', required: false, defaultValue: 'true', description: 'If true, files are not deleted after processing' },
      { name: 'delay', label: 'Poll Delay (ms)', type: 'integer', required: false, defaultValue: '5000', description: 'Polling interval in milliseconds' },
      { name: 'include', label: 'Include Pattern', type: 'string', required: false, description: 'Regex or glob for file names to include' },
    ],
  },
  {
    scheme: 'kafka',
    label: 'Kafka Consumer',
    description: 'Consume messages from an Apache Kafka topic',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'kafka:my-topic',
    defaultRawStep: { uri: 'kafka:my-topic', parameters: { brokers: 'localhost:9092', groupId: 'my-group' } },
    params: [
      { name: 'topic', label: 'Topic', type: 'string', required: true, defaultValue: 'my-topic', description: 'Kafka topic name', isUriPath: true },
      { name: 'brokers', label: 'Brokers', type: 'string', required: true, defaultValue: 'localhost:9092', description: 'Comma-separated list of Kafka brokers' },
      { name: 'groupId', label: 'Consumer Group', type: 'string', required: true, defaultValue: 'my-group', description: 'Kafka consumer group ID' },
      { name: 'autoOffsetReset', label: 'Auto Offset Reset', type: 'string', required: false, defaultValue: 'latest', description: 'earliest or latest' },
    ],
  },
  {
    scheme: 'activemq',
    label: 'ActiveMQ Consumer',
    description: 'Consume messages from Apache ActiveMQ',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'activemq:queue:myQueue',
    defaultRawStep: { uri: 'activemq:queue:myQueue' },
    params: [
      { name: 'destinationType', label: 'Destination Type', type: 'string', required: true, defaultValue: 'queue', description: 'queue or topic', isUriPath: true },
      { name: 'destinationName', label: 'Destination Name', type: 'string', required: true, defaultValue: 'myQueue', description: 'Queue or topic name', isUriPath: true },
    ],
  },
  {
    scheme: 'rabbitmq',
    label: 'RabbitMQ Consumer',
    description: 'Consume messages from RabbitMQ',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'rabbitmq:myExchange',
    defaultRawStep: { uri: 'rabbitmq:myExchange', parameters: { hostname: 'localhost', portNumber: '5672', queue: 'myQueue' } },
    params: [
      { name: 'exchangeName', label: 'Exchange', type: 'string', required: true, defaultValue: 'myExchange', description: 'RabbitMQ exchange name', isUriPath: true },
      { name: 'hostname', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'RabbitMQ host' },
      { name: 'portNumber', label: 'Port', type: 'integer', required: false, defaultValue: '5672', description: 'RabbitMQ port' },
      { name: 'queue', label: 'Queue', type: 'string', required: false, description: 'Queue to bind to' },
    ],
  },
  {
    scheme: 'aws2-sqs-consumer',
    label: 'AWS SQS Consumer',
    description: 'Receive messages from Amazon SQS',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'aws2-sqs:myQueue',
    defaultRawStep: { uri: 'aws2-sqs:myQueue', parameters: { region: 'us-east-1' } },
    params: [
      { name: 'queueNameOrArn', label: 'Queue Name or ARN', type: 'string', required: true, defaultValue: 'myQueue', description: 'SQS queue name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'accessKey', label: 'Access Key', type: 'password', required: false, description: 'AWS access key (prefer IAM role)' },
      { name: 'secretKey', label: 'Secret Key', type: 'password', required: false, description: 'AWS secret key (prefer IAM role)' },
    ],
  },
  {
    scheme: 'azure-servicebus-consumer',
    label: 'Azure Service Bus Consumer',
    description: 'Receive messages from Azure Service Bus',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'azure-servicebus:myQueue',
    defaultRawStep: { uri: 'azure-servicebus:myQueue', parameters: { connectionString: '{{servicebus.connection.string}}' } },
    params: [
      { name: 'topicOrQueueName', label: 'Queue / Topic', type: 'string', required: true, defaultValue: 'myQueue', description: 'Service Bus queue or topic name', isUriPath: true },
      { name: 'connectionString', label: 'Connection String', type: 'password', required: true, description: 'Azure Service Bus connection string' },
    ],
  },
  {
    scheme: 'cron',
    label: 'Cron',
    description: 'Schedule using a cron expression (lightweight, no Quartz dependency)',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'cron:myJob',
    defaultRawStep: { uri: 'cron:myJob', parameters: { schedule: '0/5 * * * * ?' } },
    params: [
      { name: 'name', label: 'Job Name', type: 'string', required: true, defaultValue: 'myJob', description: 'Cron job name', isUriPath: true },
      { name: 'schedule', label: 'Schedule', type: 'string', required: true, defaultValue: '0/5 * * * * ?', description: 'Cron expression' },
    ],
  },
  {
    scheme: 'ftp-consumer',
    label: 'FTP Consumer',
    description: 'Poll an FTP server for new files',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'ftp:localhost/inbox',
    defaultRawStep: { uri: 'ftp:localhost/inbox', parameters: { username: 'user', password: '{{ftp.password}}' } },
    params: [
      { name: 'host', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'FTP server hostname', isUriPath: true },
      { name: 'directoryName', label: 'Directory', type: 'string', required: false, defaultValue: 'inbox', description: 'Remote directory path', isUriPath: true },
      { name: 'username', label: 'Username', type: 'string', required: true, description: 'FTP username' },
      { name: 'password', label: 'Password', type: 'password', required: true, description: 'FTP password' },
    ],
  },
  {
    scheme: 'sftp-consumer',
    label: 'SFTP Consumer',
    description: 'Poll an SFTP server for new files',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'sftp:localhost/inbox',
    defaultRawStep: { uri: 'sftp:localhost/inbox', parameters: { username: 'user', password: '{{sftp.password}}' } },
    params: [
      { name: 'host', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'SFTP server hostname', isUriPath: true },
      { name: 'directoryName', label: 'Directory', type: 'string', required: false, defaultValue: 'inbox', description: 'Remote directory path', isUriPath: true },
      { name: 'username', label: 'Username', type: 'string', required: true, description: 'SFTP username' },
      { name: 'password', label: 'Password', type: 'password', required: false, description: 'SFTP password' },
      { name: 'privateKeyFile', label: 'Private Key File', type: 'string', required: false, description: 'Path to private key file' },
    ],
  },
  {
    scheme: 'aws2-s3-consumer',
    label: 'AWS S3 Consumer',
    description: 'Poll an S3 bucket for new objects',
    category: 'Trigger',
    stepKind: 'from',
    defaultUri: 'aws2-s3:my-bucket',
    defaultRawStep: { uri: 'aws2-s3:my-bucket', parameters: { region: 'us-east-1', deleteAfterRead: 'false' } },
    params: [
      { name: 'bucketNameOrArn', label: 'Bucket', type: 'string', required: true, defaultValue: 'my-bucket', description: 'S3 bucket name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'deleteAfterRead', label: 'Delete After Read', type: 'boolean', required: false, defaultValue: 'false', description: 'Remove objects from S3 after consuming' },
    ],
  },

  // ── HTTP ─────────────────────────────────────────────────────────────────
  {
    scheme: 'https',
    label: 'HTTPS',
    description: 'Make an outbound HTTPS call',
    category: 'HTTP',
    stepKind: 'to',
    defaultUri: 'https://api.example.com/endpoint',
    defaultRawStep: { uri: 'https://api.example.com/endpoint' },
    params: [
      { name: 'uri', label: 'URL', type: 'string', required: true, defaultValue: 'https://api.example.com/endpoint', description: 'Full HTTPS URL including path', isUriPath: true },
      { name: 'httpMethod', label: 'HTTP Method', type: 'string', required: false, defaultValue: 'GET', description: 'GET, POST, PUT, DELETE, etc.' },
      { name: 'connectTimeout', label: 'Connect Timeout (ms)', type: 'integer', required: false, defaultValue: '10000', description: 'Connection timeout' },
      { name: 'socketTimeout', label: 'Socket Timeout (ms)', type: 'integer', required: false, defaultValue: '10000', description: 'Read timeout' },
    ],
  },
  {
    scheme: 'http',
    label: 'HTTP',
    description: 'Make an outbound HTTP call',
    category: 'HTTP',
    stepKind: 'to',
    defaultUri: 'http://api.example.com/endpoint',
    defaultRawStep: { uri: 'http://api.example.com/endpoint' },
    params: [
      { name: 'uri', label: 'URL', type: 'string', required: true, defaultValue: 'http://api.example.com/endpoint', description: 'Full HTTP URL including path', isUriPath: true },
      { name: 'httpMethod', label: 'HTTP Method', type: 'string', required: false, defaultValue: 'GET', description: 'GET, POST, PUT, DELETE, etc.' },
    ],
  },
  {
    scheme: 'rest-openapi',
    label: 'REST via OpenAPI',
    description: 'Call a REST API defined by an OpenAPI spec',
    category: 'HTTP',
    stepKind: 'to',
    defaultUri: 'rest-openapi:petstore.json#getPets',
    defaultRawStep: { uri: 'rest-openapi:petstore.json#getPets' },
    params: [
      { name: 'specificationUri', label: 'OpenAPI Spec URI', type: 'string', required: true, description: 'Path or URL to the OpenAPI spec file', isUriPath: true },
      { name: 'operationId', label: 'Operation ID', type: 'string', required: true, description: 'OpenAPI operationId to call', isUriPath: true },
      { name: 'host', label: 'Override Host', type: 'string', required: false, description: 'Override the server host from the spec' },
    ],
  },
  {
    scheme: 'netty-http',
    label: 'Netty HTTP',
    description: 'High-performance HTTP using Netty',
    category: 'HTTP',
    stepKind: 'to',
    defaultUri: 'netty-http:http://0.0.0.0:8080/path',
    defaultRawStep: { uri: 'netty-http:http://0.0.0.0:8080/path' },
    params: [
      { name: 'uri', label: 'Netty URI', type: 'string', required: true, description: 'Full Netty HTTP URI', isUriPath: true },
    ],
  },

  // ── File & IO ────────────────────────────────────────────────────────────
  {
    scheme: 'file',
    label: 'File Producer',
    description: 'Write message to a file on disk',
    category: 'File & IO',
    stepKind: 'to',
    defaultUri: 'file:data/outbox',
    defaultRawStep: { uri: 'file:data/outbox' },
    params: [
      { name: 'directoryName', label: 'Directory', type: 'string', required: true, defaultValue: 'data/outbox', description: 'Directory to write to', isUriPath: true },
      { name: 'fileName', label: 'File Name', type: 'string', required: false, description: 'File name expression (supports ${header.CamelFileName})' },
      { name: 'fileExist', label: 'If File Exists', type: 'string', required: false, defaultValue: 'Override', description: 'Override, Append, Fail, Ignore, TryRename' },
    ],
  },
  {
    scheme: 'ftp',
    label: 'FTP Producer',
    description: 'Upload a file to an FTP server',
    category: 'File & IO',
    stepKind: 'to',
    defaultUri: 'ftp:localhost/outbox',
    defaultRawStep: { uri: 'ftp:localhost/outbox', parameters: { username: 'user', password: '{{ftp.password}}' } },
    params: [
      { name: 'host', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'FTP server hostname', isUriPath: true },
      { name: 'directoryName', label: 'Directory', type: 'string', required: false, defaultValue: 'outbox', description: 'Remote directory path', isUriPath: true },
      { name: 'username', label: 'Username', type: 'string', required: true, description: 'FTP username' },
      { name: 'password', label: 'Password', type: 'password', required: true, description: 'FTP password' },
    ],
  },
  {
    scheme: 'sftp',
    label: 'SFTP Producer',
    description: 'Upload a file to an SFTP server',
    category: 'File & IO',
    stepKind: 'to',
    defaultUri: 'sftp:localhost/outbox',
    defaultRawStep: { uri: 'sftp:localhost/outbox', parameters: { username: 'user', password: '{{sftp.password}}' } },
    params: [
      { name: 'host', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'SFTP server hostname', isUriPath: true },
      { name: 'directoryName', label: 'Directory', type: 'string', required: false, defaultValue: 'outbox', description: 'Remote directory path', isUriPath: true },
      { name: 'username', label: 'Username', type: 'string', required: true, description: 'SFTP username' },
      { name: 'password', label: 'Password', type: 'password', required: false, description: 'SFTP password' },
    ],
  },
  {
    scheme: 'smb',
    label: 'SMB / CIFS',
    description: 'Read or write files on a Windows/SMB file share',
    category: 'File & IO',
    stepKind: 'to',
    defaultUri: 'smb:myhost/share',
    defaultRawStep: { uri: 'smb:myhost/share', parameters: { username: 'user', password: '{{smb.password}}' } },
    params: [
      { name: 'hostname', label: 'Host', type: 'string', required: true, description: 'SMB server hostname', isUriPath: true },
      { name: 'shareName', label: 'Share', type: 'string', required: true, description: 'SMB share name', isUriPath: true },
      { name: 'username', label: 'Username', type: 'string', required: true, description: 'SMB username' },
      { name: 'password', label: 'Password', type: 'password', required: true, description: 'SMB password' },
    ],
  },
  {
    scheme: 'stream',
    label: 'Stream Out',
    description: 'Write message body to stdout or stderr',
    category: 'File & IO',
    stepKind: 'to',
    defaultUri: 'stream:out',
    defaultRawStep: { uri: 'stream:out' },
    params: [
      { name: 'kind', label: 'Stream Kind', type: 'string', required: true, defaultValue: 'out', description: 'out, err, or file', isUriPath: true },
    ],
  },

  // ── Messaging ────────────────────────────────────────────────────────────
  {
    scheme: 'kafka-producer',
    label: 'Kafka Producer',
    description: 'Publish messages to an Apache Kafka topic',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'kafka:my-topic',
    defaultRawStep: { uri: 'kafka:my-topic', parameters: { brokers: 'localhost:9092' } },
    params: [
      { name: 'topic', label: 'Topic', type: 'string', required: true, defaultValue: 'my-topic', description: 'Kafka topic name', isUriPath: true },
      { name: 'brokers', label: 'Brokers', type: 'string', required: true, defaultValue: 'localhost:9092', description: 'Comma-separated list of Kafka brokers' },
      { name: 'key', label: 'Message Key', type: 'expression', required: false, description: 'Expression for partition key' },
    ],
  },
  {
    scheme: 'activemq-producer',
    label: 'ActiveMQ Producer',
    description: 'Send messages to Apache ActiveMQ',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'activemq:queue:myQueue',
    defaultRawStep: { uri: 'activemq:queue:myQueue' },
    params: [
      { name: 'destinationType', label: 'Destination Type', type: 'string', required: true, defaultValue: 'queue', description: 'queue or topic', isUriPath: true },
      { name: 'destinationName', label: 'Destination Name', type: 'string', required: true, defaultValue: 'myQueue', description: 'Queue or topic name', isUriPath: true },
      { name: 'deliveryMode', label: 'Delivery Mode', type: 'integer', required: false, defaultValue: '1', description: '1=non-persistent, 2=persistent' },
    ],
  },
  {
    scheme: 'rabbitmq-producer',
    label: 'RabbitMQ Producer',
    description: 'Publish messages to RabbitMQ',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'rabbitmq:myExchange',
    defaultRawStep: { uri: 'rabbitmq:myExchange', parameters: { hostname: 'localhost', portNumber: '5672' } },
    params: [
      { name: 'exchangeName', label: 'Exchange', type: 'string', required: true, defaultValue: 'myExchange', description: 'RabbitMQ exchange name', isUriPath: true },
      { name: 'hostname', label: 'Host', type: 'string', required: true, defaultValue: 'localhost', description: 'RabbitMQ host' },
      { name: 'portNumber', label: 'Port', type: 'integer', required: false, defaultValue: '5672', description: 'RabbitMQ port' },
      { name: 'routingKey', label: 'Routing Key', type: 'string', required: false, description: 'RabbitMQ routing key' },
    ],
  },
  {
    scheme: 'aws2-sqs',
    label: 'AWS SQS Producer',
    description: 'Send messages to Amazon SQS',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'aws2-sqs:myQueue',
    defaultRawStep: { uri: 'aws2-sqs:myQueue', parameters: { region: 'us-east-1' } },
    params: [
      { name: 'queueNameOrArn', label: 'Queue Name or ARN', type: 'string', required: true, defaultValue: 'myQueue', description: 'SQS queue name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'messageGroupIdStrategy', label: 'Message Group ID', type: 'string', required: false, description: 'For FIFO queues' },
    ],
  },
  {
    scheme: 'aws2-sns',
    label: 'AWS SNS',
    description: 'Publish a notification to Amazon SNS',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'aws2-sns:myTopic',
    defaultRawStep: { uri: 'aws2-sns:myTopic', parameters: { region: 'us-east-1' } },
    params: [
      { name: 'topicNameOrArn', label: 'Topic Name or ARN', type: 'string', required: true, defaultValue: 'myTopic', description: 'SNS topic name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'subject', label: 'Subject', type: 'string', required: false, description: 'Email subject for email subscribers' },
    ],
  },
  {
    scheme: 'azure-servicebus',
    label: 'Azure Service Bus Producer',
    description: 'Send messages to Azure Service Bus',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'azure-servicebus:myQueue',
    defaultRawStep: { uri: 'azure-servicebus:myQueue', parameters: { connectionString: '{{servicebus.connection.string}}' } },
    params: [
      { name: 'topicOrQueueName', label: 'Queue / Topic', type: 'string', required: true, defaultValue: 'myQueue', description: 'Service Bus queue or topic name', isUriPath: true },
      { name: 'connectionString', label: 'Connection String', type: 'password', required: true, description: 'Azure Service Bus connection string' },
    ],
  },
  {
    scheme: 'jms',
    label: 'JMS',
    description: 'Send to a JMS queue or topic',
    category: 'Messaging',
    stepKind: 'to',
    defaultUri: 'jms:queue:myQueue',
    defaultRawStep: { uri: 'jms:queue:myQueue' },
    params: [
      { name: 'destinationType', label: 'Destination Type', type: 'string', required: true, defaultValue: 'queue', description: 'queue or topic', isUriPath: true },
      { name: 'destinationName', label: 'Destination Name', type: 'string', required: true, defaultValue: 'myQueue', description: 'Queue or topic name', isUriPath: true },
      { name: 'deliveryPersistent', label: 'Persistent Delivery', type: 'boolean', required: false, defaultValue: 'true', description: 'Enable persistent delivery mode' },
    ],
  },

  // ── Cloud ────────────────────────────────────────────────────────────────
  {
    scheme: 'aws2-s3',
    label: 'AWS S3 Put',
    description: 'Upload an object to Amazon S3',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'aws2-s3:my-bucket',
    defaultRawStep: { uri: 'aws2-s3:my-bucket', parameters: { region: 'us-east-1', keyName: 'output.json' } },
    params: [
      { name: 'bucketNameOrArn', label: 'Bucket', type: 'string', required: true, defaultValue: 'my-bucket', description: 'S3 bucket name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'keyName', label: 'Object Key', type: 'string', required: false, description: 'S3 object key (file name)' },
    ],
  },
  {
    scheme: 'aws2-dynamodb',
    label: 'AWS DynamoDB',
    description: 'Put or query items in Amazon DynamoDB',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'aws2-ddb:myTable',
    defaultRawStep: { uri: 'aws2-ddb:myTable', parameters: { region: 'us-east-1', operation: 'PutItem' } },
    params: [
      { name: 'tableName', label: 'Table', type: 'string', required: true, defaultValue: 'myTable', description: 'DynamoDB table name', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'operation', label: 'Operation', type: 'string', required: false, defaultValue: 'PutItem', description: 'PutItem, GetItem, Query, Scan, DeleteItem, UpdateItem' },
    ],
  },
  {
    scheme: 'aws2-lambda',
    label: 'AWS Lambda',
    description: 'Invoke an AWS Lambda function',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'aws2-lambda:myFunction',
    defaultRawStep: { uri: 'aws2-lambda:myFunction', parameters: { region: 'us-east-1' } },
    params: [
      { name: 'function', label: 'Function Name', type: 'string', required: true, defaultValue: 'myFunction', description: 'Lambda function name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
      { name: 'qualifier', label: 'Qualifier', type: 'string', required: false, description: 'Function version or alias' },
    ],
  },
  {
    scheme: 'aws2-eventbridge',
    label: 'AWS EventBridge',
    description: 'Put events to Amazon EventBridge',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'aws2-eventbridge:default',
    defaultRawStep: { uri: 'aws2-eventbridge:default', parameters: { region: 'us-east-1' } },
    params: [
      { name: 'eventbusNameOrArn', label: 'Event Bus', type: 'string', required: true, defaultValue: 'default', description: 'Event bus name or ARN', isUriPath: true },
      { name: 'region', label: 'AWS Region', type: 'string', required: true, defaultValue: 'us-east-1', description: 'AWS region' },
    ],
  },
  {
    scheme: 'azure-storage-blob',
    label: 'Azure Blob Storage',
    description: 'Upload a blob to Azure Blob Storage',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'azure-storage-blob:myAccount/myContainer',
    defaultRawStep: { uri: 'azure-storage-blob:myAccount/myContainer', parameters: { connectionString: '{{azure.storage.connection.string}}' } },
    params: [
      { name: 'accountName', label: 'Account', type: 'string', required: true, description: 'Azure storage account name', isUriPath: true },
      { name: 'containerName', label: 'Container', type: 'string', required: true, description: 'Blob container name', isUriPath: true },
      { name: 'connectionString', label: 'Connection String', type: 'password', required: false, description: 'Azure storage connection string' },
    ],
  },
  {
    scheme: 'google-pubsub',
    label: 'Google Cloud Pub/Sub',
    description: 'Publish to or subscribe from Google Cloud Pub/Sub',
    category: 'Cloud',
    stepKind: 'to',
    defaultUri: 'google-pubsub:my-project:myTopic',
    defaultRawStep: { uri: 'google-pubsub:my-project:myTopic' },
    params: [
      { name: 'projectId', label: 'Project ID', type: 'string', required: true, description: 'Google Cloud project ID', isUriPath: true },
      { name: 'destinationName', label: 'Topic or Subscription', type: 'string', required: true, description: 'Pub/Sub topic or subscription name', isUriPath: true },
    ],
  },

  // ── Database ─────────────────────────────────────────────────────────────
  {
    scheme: 'jdbc',
    label: 'JDBC',
    description: 'Execute a SQL query via JDBC',
    category: 'Database',
    stepKind: 'to',
    defaultUri: 'jdbc:dataSource',
    defaultRawStep: { uri: 'jdbc:dataSource' },
    params: [
      { name: 'dataSourceName', label: 'Data Source', type: 'string', required: true, defaultValue: 'dataSource', description: 'JNDI name of the DataSource bean', isUriPath: true },
      { name: 'readSize', label: 'Read Size', type: 'integer', required: false, description: 'Max rows to read' },
    ],
  },
  {
    scheme: 'sql',
    label: 'SQL',
    description: 'Execute a SQL statement with named parameters',
    category: 'Database',
    stepKind: 'to',
    defaultUri: 'sql:SELECT * FROM users WHERE id = :#id',
    defaultRawStep: { uri: 'sql:SELECT * FROM users WHERE id = :#id', parameters: { dataSource: 'dataSource' } },
    params: [
      { name: 'query', label: 'SQL Query', type: 'string', required: true, defaultValue: 'SELECT * FROM users WHERE id = :#id', description: 'SQL statement with :#param placeholders', isUriPath: true },
      { name: 'dataSource', label: 'Data Source', type: 'string', required: true, defaultValue: 'dataSource', description: 'Bean name of the DataSource' },
    ],
  },
  {
    scheme: 'mybatis',
    label: 'MyBatis',
    description: 'Execute a MyBatis mapped statement',
    category: 'Database',
    stepKind: 'to',
    defaultUri: 'mybatis:selectUser',
    defaultRawStep: { uri: 'mybatis:selectUser', parameters: { statementType: 'SelectOne' } },
    params: [
      { name: 'statement', label: 'Statement', type: 'string', required: true, defaultValue: 'selectUser', description: 'MyBatis statement ID', isUriPath: true },
      { name: 'statementType', label: 'Statement Type', type: 'string', required: false, defaultValue: 'SelectOne', description: 'SelectOne, SelectList, Insert, Update, Delete' },
    ],
  },
  {
    scheme: 'mongodb',
    label: 'MongoDB',
    description: 'Read from or write to MongoDB',
    category: 'Database',
    stepKind: 'to',
    defaultUri: 'mongodb:myMongoClient',
    defaultRawStep: { uri: 'mongodb:myMongoClient', parameters: { database: 'mydb', collection: 'mycoll', operation: 'findAll' } },
    params: [
      { name: 'connectionBean', label: 'Connection Bean', type: 'string', required: true, defaultValue: 'myMongoClient', description: 'Spring bean name for MongoClient', isUriPath: true },
      { name: 'database', label: 'Database', type: 'string', required: true, description: 'MongoDB database name' },
      { name: 'collection', label: 'Collection', type: 'string', required: true, description: 'MongoDB collection name' },
      { name: 'operation', label: 'Operation', type: 'string', required: true, defaultValue: 'findAll', description: 'findAll, findById, insert, update, remove, etc.' },
    ],
  },
  {
    scheme: 'elasticsearch-rest',
    label: 'Elasticsearch',
    description: 'Index or query documents in Elasticsearch',
    category: 'Database',
    stepKind: 'to',
    defaultUri: 'elasticsearch-rest:myCluster',
    defaultRawStep: { uri: 'elasticsearch-rest:myCluster', parameters: { hostAddresses: 'localhost:9200', operation: 'INDEX', indexName: 'my-index' } },
    params: [
      { name: 'clusterName', label: 'Cluster Name', type: 'string', required: true, defaultValue: 'myCluster', description: 'Elasticsearch cluster name', isUriPath: true },
      { name: 'hostAddresses', label: 'Host:Port', type: 'string', required: true, defaultValue: 'localhost:9200', description: 'Comma-separated host:port list' },
      { name: 'operation', label: 'Operation', type: 'string', required: true, defaultValue: 'INDEX', description: 'INDEX, GET_BY_ID, DELETE, SEARCH, etc.' },
      { name: 'indexName', label: 'Index', type: 'string', required: false, description: 'Target Elasticsearch index' },
    ],
  },

  // ── Data Format ──────────────────────────────────────────────────────────
  {
    scheme: 'marshal',
    label: 'Marshal',
    description: 'Serialize the message body to a data format (e.g. JSON)',
    category: 'Data Format',
    stepKind: 'marshal',
    defaultRawStep: { json: {} },
    params: [
      { name: 'format', label: 'Format', type: 'string', required: true, defaultValue: 'json', description: 'Data format: json, xml, csv, avro, protobuf, etc.' },
    ],
  },
  {
    scheme: 'unmarshal',
    label: 'Unmarshal',
    description: 'Deserialize the message body from a data format (e.g. JSON)',
    category: 'Data Format',
    stepKind: 'unmarshal',
    defaultRawStep: { json: {} },
    params: [
      { name: 'format', label: 'Format', type: 'string', required: true, defaultValue: 'json', description: 'Data format: json, xml, csv, avro, protobuf, etc.' },
    ],
  },

  // ── Transformation ───────────────────────────────────────────────────────
  {
    scheme: 'transform',
    label: 'Transform',
    description: 'Transform the message body using an expression',
    category: 'Transformation',
    stepKind: 'transform',
    defaultRawStep: { simple: '${body}' },
    params: [
      { name: 'expression', label: 'Expression', type: 'expression', required: true, defaultValue: '${body}', description: 'Transformation expression (Simple, JQ, Constant, etc.)' },
      { name: 'expressionType', label: 'Expression Type', type: 'string', required: false, defaultValue: 'simple', description: 'simple, jq, constant, groovy, etc.' },
    ],
  },
  {
    scheme: 'setBody',
    label: 'Set Body',
    description: 'Set the message body to an expression result',
    category: 'Transformation',
    stepKind: 'setBody',
    defaultRawStep: { constant: 'new body value' },
    params: [
      { name: 'expression', label: 'Expression', type: 'expression', required: true, defaultValue: 'new body value', description: 'Body expression' },
      { name: 'expressionType', label: 'Expression Type', type: 'string', required: false, defaultValue: 'constant', description: 'constant, simple, jq, etc.' },
    ],
  },
  {
    scheme: 'setHeader',
    label: 'Set Header',
    description: 'Set a named header on the message',
    category: 'Transformation',
    stepKind: 'setHeader',
    defaultRawStep: { name: 'myHeader', constant: 'headerValue' },
    params: [
      { name: 'name', label: 'Header Name', type: 'string', required: true, defaultValue: 'myHeader', description: 'Name of the header to set' },
      { name: 'expression', label: 'Value Expression', type: 'expression', required: true, defaultValue: 'headerValue', description: 'Header value expression' },
      { name: 'expressionType', label: 'Expression Type', type: 'string', required: false, defaultValue: 'constant', description: 'constant, simple, jq, etc.' },
    ],
  },
  {
    scheme: 'removeHeaders',
    label: 'Remove Headers',
    description: 'Remove headers matching a pattern',
    category: 'Transformation',
    stepKind: 'removeHeaders',
    defaultRawStep: { pattern: 'CamelHttp*' },
    params: [
      { name: 'pattern', label: 'Pattern', type: 'string', required: true, defaultValue: 'CamelHttp*', description: 'Header name pattern to remove (glob or regex)' },
    ],
  },

  // ── Routing ──────────────────────────────────────────────────────────────
  {
    scheme: 'filter',
    label: 'Filter',
    description: 'Pass only messages matching a predicate',
    category: 'Routing',
    stepKind: 'filter',
    defaultRawStep: { simple: '${body} != null' },
    params: [
      { name: 'expression', label: 'Filter Expression', type: 'expression', required: true, defaultValue: '${body} != null', description: 'Predicate expression — messages that evaluate to true pass through' },
      { name: 'expressionType', label: 'Expression Type', type: 'string', required: false, defaultValue: 'simple', description: 'simple, xpath, jq, etc.' },
    ],
  },
  {
    scheme: 'choice',
    label: 'Choice (Router)',
    description: 'Content-based routing with when/otherwise branches',
    category: 'Routing',
    stepKind: 'choice',
    defaultRawStep: { when: [{ simple: '${header.type} == "A"', steps: [] }], otherwise: { steps: [] } },
    params: [],
  },
  {
    scheme: 'split',
    label: 'Splitter',
    description: 'Split a message into multiple sub-messages',
    category: 'Routing',
    stepKind: 'to',
    defaultUri: '',
    defaultRawStep: {},
    params: [
      { name: 'expression', label: 'Split Expression', type: 'expression', required: true, defaultValue: '${body}', description: 'Expression returning an iterable to split on' },
      { name: 'parallelProcessing', label: 'Parallel Processing', type: 'boolean', required: false, defaultValue: 'false', description: 'Process sub-messages in parallel' },
    ],
  },
  {
    scheme: 'aggregate',
    label: 'Aggregator',
    description: 'Aggregate multiple messages into one',
    category: 'Routing',
    stepKind: 'to',
    defaultUri: '',
    defaultRawStep: {},
    params: [
      { name: 'correlationExpression', label: 'Correlation Key', type: 'expression', required: true, description: 'Expression to correlate messages' },
      { name: 'completionSize', label: 'Completion Size', type: 'integer', required: false, description: 'Complete when N messages are aggregated' },
      { name: 'completionTimeout', label: 'Completion Timeout (ms)', type: 'integer', required: false, description: 'Complete after this timeout' },
    ],
  },
  {
    scheme: 'doTry',
    label: 'Try / Catch',
    description: 'Wrap steps in a try/catch block',
    category: 'Routing',
    stepKind: 'doTry',
    defaultRawStep: { steps: [], doCatch: [{ exception: ['java.lang.Exception'], steps: [] }] },
    params: [],
  },
  {
    scheme: 'loop',
    label: 'Loop',
    description: 'Repeat a sub-route a fixed number of times',
    category: 'Routing',
    stepKind: 'to',
    defaultUri: '',
    defaultRawStep: {},
    params: [
      { name: 'expression', label: 'Loop Count', type: 'expression', required: true, defaultValue: '3', description: 'Number of iterations (constant or expression)' },
      { name: 'copy', label: 'Copy Exchange', type: 'boolean', required: false, defaultValue: 'false', description: 'If true, a fresh copy of the exchange is used each iteration' },
    ],
  },
  {
    scheme: 'multicast',
    label: 'Multicast',
    description: 'Send the same message to multiple endpoints',
    category: 'Routing',
    stepKind: 'to',
    defaultUri: '',
    defaultRawStep: {},
    params: [
      { name: 'parallelProcessing', label: 'Parallel Processing', type: 'boolean', required: false, defaultValue: 'false', description: 'Send to all endpoints in parallel' },
      { name: 'stopOnException', label: 'Stop on Exception', type: 'boolean', required: false, defaultValue: 'false', description: 'Stop processing remaining endpoints on error' },
    ],
  },
  {
    scheme: 'recipientList',
    label: 'Recipient List',
    description: 'Route to a dynamic list of endpoints',
    category: 'Routing',
    stepKind: 'to',
    defaultUri: '',
    defaultRawStep: {},
    params: [
      { name: 'expression', label: 'Recipients Expression', type: 'expression', required: true, description: 'Expression returning comma-separated endpoint URIs' },
      { name: 'delimiter', label: 'Delimiter', type: 'string', required: false, defaultValue: ',', description: 'Separator between endpoint URIs' },
    ],
  },

  // ── Error Handling ───────────────────────────────────────────────────────
  {
    scheme: 'onException',
    label: 'On Exception',
    description: 'Route-level exception handler',
    category: 'Error Handling',
    stepKind: 'onException',
    defaultRawStep: { exception: ['java.lang.Exception'], redeliveryPolicy: { maximumRedeliveries: '3' } },
    params: [
      { name: 'exception', label: 'Exception Class', type: 'string', required: true, defaultValue: 'java.lang.Exception', description: 'Fully-qualified exception class to catch' },
      { name: 'maximumRedeliveries', label: 'Max Redeliveries', type: 'integer', required: false, defaultValue: '3', description: 'Number of redelivery attempts before dead-lettering' },
      { name: 'redeliveryDelay', label: 'Redelivery Delay (ms)', type: 'integer', required: false, defaultValue: '1000', description: 'Delay between redelivery attempts' },
    ],
  },
  {
    scheme: 'log',
    label: 'Log',
    description: 'Log a message to the route logger',
    category: 'Error Handling',
    stepKind: 'log',
    defaultRawStep: { message: '${body}', loggingLevel: 'INFO' },
    params: [
      { name: 'message', label: 'Message', type: 'expression', required: true, defaultValue: '${body}', description: 'Message to log (supports Simple expressions)' },
      { name: 'loggingLevel', label: 'Level', type: 'string', required: false, defaultValue: 'INFO', description: 'DEBUG, INFO, WARN, ERROR' },
      { name: 'logName', label: 'Logger Name', type: 'string', required: false, description: 'Custom logger name' },
    ],
  },
  {
    scheme: 'deadLetterChannel',
    label: 'Dead Letter Channel',
    description: 'Send failed messages to a dead-letter endpoint',
    category: 'Error Handling',
    stepKind: 'to',
    defaultUri: 'file:data/dead',
    defaultRawStep: { uri: 'file:data/dead' },
    params: [
      { name: 'deadLetterUri', label: 'Dead Letter URI', type: 'string', required: true, defaultValue: 'file:data/dead', description: 'Endpoint URI for undeliverable messages', isUriPath: true },
      { name: 'maximumRedeliveries', label: 'Max Redeliveries', type: 'integer', required: false, defaultValue: '3', description: 'Number of retries before dead-lettering' },
      { name: 'redeliveryDelay', label: 'Redelivery Delay (ms)', type: 'integer', required: false, defaultValue: '1000', description: 'Delay between retries' },
    ],
  },
]

/** Look up a component definition by its scheme identifier */
export function findComponentByScheme(scheme: string): CamelComponentDef | undefined {
  return CAMEL_COMPONENT_REGISTRY.find((c) => c.scheme === scheme)
}

/** Get all unique categories in display order */
export const COMPONENT_CATEGORIES: ComponentCategory[] = [
  'Trigger',
  'HTTP',
  'File & IO',
  'Messaging',
  'Cloud',
  'Database',
  'Data Format',
  'Transformation',
  'Routing',
  'Error Handling',
]
