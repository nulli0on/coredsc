import {existsSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const projectRoot = dirname(fileURLToPath(import.meta.url));
const hasGitHistory = existsSync(join(projectRoot, '.git'));
const showGitMetadata = process.env.DOCS_SHOW_LAST_UPDATE
  ? process.env.DOCS_SHOW_LAST_UPDATE === 'true'
  : hasGitHistory;

const siteUrl = (process.env.DOCS_SITE_URL || 'https://hubertstudios.com').replace(/\/+$/, '');
const rawBaseUrl = process.env.DOCS_BASE_URL || '/';
const baseUrl = rawBaseUrl === '/' ? '/' : `/${rawBaseUrl.replace(/^\/+|\/+$/g, '')}/`;
const repositoryUrl = process.env.DOCS_REPOSITORY_URL || '';
const modrinthUrl = process.env.MODRINTH_URL || '';

const projectLinks = [
  {href: 'https://hubertstudios.com', label: 'Website', position: 'right'},
  ...(modrinthUrl ? [{href: modrinthUrl, label: 'Modrinth', position: 'right'}] : []),
  ...(repositoryUrl ? [{href: repositoryUrl, label: 'GitHub', position: 'right'}] : []),
];

const config = {
  title: 'CoreDSC Documentation',
  tagline: 'Discord integration for Paper servers.',
  favicon: 'assets/logo.svg',
  url: siteUrl,
  baseUrl,
  organizationName: 'HubertStudios',
  projectName: 'CoreDSC-Documentation',
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',
  trailingSlash: false,
  i18n: {defaultLocale: 'en', locales: ['en']},
  presets: [[
    'classic',
    {
      docs: {
        routeBasePath: '/',
        sidebarPath: './config/sidebars.js',
        breadcrumbs: false,
        editUrl: repositoryUrl ? `${repositoryUrl}/edit/main/` : undefined,
        // ZIP/Replit builds do not contain Git history. Enable these fields only
        // in a real Git checkout, or explicitly with DOCS_SHOW_LAST_UPDATE=true.
        showLastUpdateAuthor: showGitMetadata,
        showLastUpdateTime: showGitMetadata,
      },
      blog: false,
      theme: {customCss: './src/css/custom.css'},
    },
  ]],
  themeConfig: {
    image: 'assets/logo.svg',
    metadata: [
      {name: 'theme-color', content: '#0b0d12'},
      {name: 'description', content: 'Installation, configuration and administration documentation for CoreDSC.'},
    ],
    docs: {sidebar: {hideable: false, autoCollapseCategories: false}},
    navbar: {
      title: 'CoreDSC Documentation',
      logo: {alt: 'CoreDSC logo', src: 'assets/logo.svg'},
      items: [
        {type: 'docSidebar', sidebarId: 'mainSidebar', label: 'Documentation', position: 'left'},
        {to: '/changelog', label: 'Changelog', position: 'left'},
        ...projectLinks,
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {title: 'Documentation', items: [
          {label: 'Installation', to: '/installation'},
          {label: 'Configuration', to: '/configuration'},
          {label: 'Troubleshooting', to: '/troubleshooting'},
        ]},
        {title: 'Project', items: [
          {label: 'HubertStudios', href: 'https://hubertstudios.com'},
          ...(modrinthUrl ? [{label: 'Modrinth', href: modrinthUrl}] : []),
          ...(repositoryUrl ? [{label: 'Source code', href: repositoryUrl}] : []),
          {label: 'Privacy', to: '/privacy'},
        ]},
      ],
      copyright: `Copyright © ${new Date().getFullYear()} HubertStudios. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'bash', 'json', 'yaml', 'diff'],
    },
    colorMode: {defaultMode: 'dark', disableSwitch: false, respectPrefersColorScheme: true},
  },
  plugins: [[
    '@docusaurus/plugin-client-redirects',
    {
      redirects: [
        {from: '/install', to: '/installation'},
        {from: '/config', to: '/configuration'},
        {from: '/doctor', to: '/troubleshooting'},
      ],
    },
  ]],
  themes: [[
    '@easyops-cn/docusaurus-search-local',
    {indexBlog: false, hashed: true, docsRouteBasePath: '/', language: ['en']},
  ]],
};

export default config;
